package com.controldmanager.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Looks for a newer version on GitHub Releases. Only for copies installed outside Google Play (Play
 * updates its own installs, and its policy doesn't allow another update path); test builds skip it too.
 * Nothing is installed by the app: the download link opens in the browser.
 */
object AppUpdates {
    data class Release(val version: String, val pageUrl: String, val apkUrl: String?) {
        val downloadUrl get() = apkUrl ?: pageUrl
    }

    private const val LATEST = "https://api.github.com/repos/tangrow1105/dns-otg/releases/latest"
    private val http by lazy { OkHttpClient() }
    private var checked = false

    /** The newer release, once a check has found one. */
    var available by mutableStateOf<Release?>(null)
        private set

    fun enabled(ctx: Context) = !ctx.packageName.endsWith(".debug") && !installedFromPlay(ctx)

    private fun installedFromPlay(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        }.getOrNull()
        return installer == "com.android.vending"
    }

    /** Checks once per app run; returns the newer release or null (also on any network error). */
    suspend fun check(ctx: Context): Release? {
        if (checked || !enabled(ctx)) return available
        checked = true
        val installed = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: return null
        val latest = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = JSONObject(r.body?.string().orEmpty())
                    val assets = o.optJSONArray("assets")
                    val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                    Release(o.optString("tag_name").removePrefix("v"), o.optString("html_url"), apk?.ifBlank { null })
                }
            }.getOrNull()
        }
        available = latest?.takeIf { it.version.isNotBlank() && isNewer(it.version, installed) }
        return available
    }

    /** Compares dotted versions number by number (1.0.10 > 1.0.9); anything after "-" is ignored. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
