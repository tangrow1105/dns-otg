package com.controldmanager.app.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the API token encrypted with an Android Keystore AES key that never leaves the device. */
object TokenStore {
    private const val ALIAS = "controldmanager_token_key"
    private const val PREFS = "controldmanager"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }

    private fun decrypt(blob: String): String? = runCatching {
        val (iv, ct) = blob.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct))
    }.getOrNull()

    fun save(ctx: Context, token: String, orgId: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", encrypt(token))
            .putString("org", orgId?.trim().orEmpty())
            .apply()
    }

    fun load(ctx: Context): Pair<String, String?>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", null)?.let(::decrypt) ?: return null
        return token to p.getString("org", null)?.takeIf { it.isNotBlank() }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** Logged-in session: the API client plus caches for catalogs that rarely change. */
class Session(val api: ControlDApi, val orgId: String?) {
    private val mutex = Mutex()
    private var proxies: List<Proxy>? = null
    private var categories: List<ServiceCategory>? = null
    private var options: List<ProfileOption>? = null
    private var deviceTypes: List<DeviceTypeGroup>? = null
    private val categoryServices = mutableMapOf<String, List<Service>>()

    suspend fun proxies() = mutex.withLock { proxies ?: api.proxies().sortedBy { it.label }.also { proxies = it } }
    suspend fun categories() = mutex.withLock { categories ?: api.serviceCategories().also { categories = it } }
    suspend fun options() = mutex.withLock { options ?: api.profileOptions().also { options = it } }
    suspend fun deviceTypes() = mutex.withLock { deviceTypes ?: api.deviceTypes().also { deviceTypes = it } }
    suspend fun servicesIn(category: String) = mutex.withLock {
        categoryServices[category] ?: api.categoryServices(category).also { categoryServices[category] = it }
    }

    private var filterNames: Map<String, String>? = null
    private var filterParents: Map<String, String> = emptyMap()

    /** Native filter id for a mode id (ads_medium → ads), used for the filter's icon. */
    suspend fun filterParent(id: String): String? { filterNames(); return mutex.withLock { filterParents[id] } }

    /**
     * Display names for filter ids seen in analytics, as the dashboard shows them:
     * "Ads & Trackers - Balanced" for a filter mode, the list's own name for 3rd-party filters.
     */
    suspend fun filterNames(): Map<String, String> {
        mutex.withLock { filterNames }?.let { return it }
        val pid = runCatching { api.profiles().firstOrNull()?.pk }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        val parents = mutableMapOf<String, String>()
        runCatching { api.nativeFilters(pid) }.getOrNull()?.forEach { f ->
            map[f.pk] = f.name
            parents[f.pk] = f.pk
            f.levels.forEach { l -> map[l.name] = "${f.name} - ${l.title}"; parents[l.name] = f.pk }
        }
        mutex.withLock { filterParents = parents }
        runCatching { api.externalFilters(pid) }.getOrNull()?.forEach { f -> map[f.pk] = f.name }
        mutex.withLock { filterNames = map }
        return map
    }

    /** Every service in the catalog, keyed by PK (used to name analytics entries). */
    suspend fun allServices(): Map<String, Service> =
        categories().flatMap { servicesIn(it.pk) }.associateBy { it.pk }

    /**
     * PK of the endpoint this phone is using, or null when it isn't using Control D.
     * Uses the verify/detect check first, then falls back to Android's Private DNS hostname.
     */
    suspend fun currentEndpointId(ctx: Context, devices: List<Device>): String? {
        api.currentResolverUid()?.let { uid -> devices.firstOrNull { it.pk == uid || it.resolvers?.uid == uid }?.let { return it.pk } }
        val host = runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName
        }.getOrNull()?.lowercase() ?: return null
        return devices.firstOrNull { d -> d.resolvers?.dot?.lowercase() == host || host.startsWith((d.resolvers?.uid ?: d.pk).lowercase() + ".") }?.pk
    }

    /** Endpoints with last activity and clients merged in (best effort; plain list if analytics fails). */
    suspend fun devicesWithActivity(regionOverride: String?): List<Device> {
        val devices = api.devices()
        val activity = runCatching { api.endpointActivity(analyticsRegion(regionOverride)) }.getOrDefault(emptyMap())
        if (activity.isEmpty()) return devices
        return devices.map { d ->
            val a = activity[d.pk] ?: activity[d.resolvers?.uid.orEmpty()] ?: return@map d.copy(activityLoaded = true)
            d.copy(lastActivity = a.lastActivity, clients = a.clients.size, clientList = a.clients, activityLoaded = true)
        }
    }

    private var detectedRegion: String? = null

    /** After the storage region changes, analytics must be read from the new host. */
    suspend fun forgetRegion() = mutex.withLock { detectedRegion = null }

    /** Analytics region: user's choice, else the account's stats_endpoint, else "america". */
    suspend fun analyticsRegion(override: String?): String {
        override?.takeIf { it.isNotBlank() }?.let { return it }
        return mutex.withLock { detectedRegion } ?: (api.user().statsRegion ?: "america").also { r -> mutex.withLock { detectedRegion = r } }
    }
}
