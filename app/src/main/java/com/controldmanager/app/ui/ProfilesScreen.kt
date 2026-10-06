@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.controldmanager.app.api.Device
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.Profile
import com.controldmanager.app.api.ProfileRef
import org.json.JSONObject

private const val PREF_SORT = "profiles_sort"
private const val SORT_NAME = "name"
private const val SORT_UPDATED = "updated"

@Composable
fun ProfilesScreen(nav: NavHostController) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val runner = rememberRunner()
    val loader = rememberLoader {
        val profiles = session.api.profiles()
        val devices = runCatching { session.devicesWithActivity(regionOverride(ctx)) }.getOrDefault(emptyList())
        profiles to devices
    }
    LiveRefresh(loader)
    val loaded = loader.data != null
    val current = rememberLoader(loaded) {
        loader.data?.second?.let { session.currentEndpointId(ctx, it) }
    }
    val uiPrefs = remember { ctx.getSharedPreferences("controldmanager_ui", Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(uiPrefs.getString(PREF_SORT, SORT_NAME) ?: SORT_NAME) }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runner.run("Profile imported", onDone = { loader.reload() }) {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: throw IllegalStateException("Couldn't read the file")
            val json = try { JSONObject(text) } catch (e: Exception) { throw IllegalStateException("That file isn't a Control D profile export") }
            // Accept the raw export, an API response wrapper, or an already-wrapped config.
            val config = json.optJSONObject("config") ?: json.optJSONObject("body") ?: json
            session.api.importProfile(config)
        }
    }

    fun setSort(v: String) {
        sort = v
        uiPrefs.edit().putString(PREF_SORT, v).apply()
    }

    NavFabButton("profiles", Solar.Add, "New profile") { creating = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Profiles", fontWeight = FontWeight.SemiBold)
                        Text("Settings enforced by your endpoints", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Solar.MoreHoriz, "Profile options") }
                        CdMenu(menu, { menu = false }) {
                            CdMenuItem(
                                { Text("Import profile") },
                                { menu = false; importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                leadingIcon = { Icon(Solar.FileUpload, null) },
                            )
                            HorizontalDivider(color = Palette.Outline)
                            Text(
                                "SORT BY", style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                            )
                            listOf(
                                Triple(SORT_NAME, "Alphabetical", Solar.SortByAlpha),
                                Triple(SORT_UPDATED, "Last updated", Solar.History),
                            ).forEach { (key, label, icon) ->
                                CdMenuItem(
                                    { Text(label) },
                                    { menu = false; setSort(key) },
                                    leadingIcon = { Icon(icon, null) },
                                    trailingIcon = { if (sort == key) Icon(Solar.Check, null, tint = Palette.Teal) },
                                )
                            }
                        }
                    }
                },
                colors = cdTopBarColors(),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { (profiles, devices) ->
            val sorted = if (sort == SORT_UPDATED) profiles.sortedByDescending { it.updated }
            else profiles.sortedBy { it.name.lowercase() }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp + LocalBottomBarSpace.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sorted.isEmpty()) item { EmptyState(Solar.Tuning, "No profiles yet. Tap + to create one.") }
                val currentDevice = devices.firstOrNull { it.pk == current.data }
                items(sorted, key = { it.pk }) { p ->
                    val endpoints = devices.filter { it.profile?.pk == p.pk || it.profile2?.pk == p.pk }
                    ProfileCard(
                        p, endpoints, currentDevice,
                        onOpenEndpoint = { nav.navigate("resolvers/${it.pk}") },
                    ) { nav.navigate("profile/${p.pk}") }
                }
            }
        }
    }

    if (creating) {
        // The dialog stays open with a spinner on Create until the profile exists, then closes.
        val done = { creating = false; loader.reload() }
        NewProfileDialog(busy = runner.busy, onDismiss = { creating = false }) { choice ->
            when (choice) {
                is NewProfile.FromType -> runner.run("${choice.type.name} created", onDone = done) {
                    session.api.createProfile(choice.type.name, choice.type.pk)
                }
                is NewProfile.Named -> runner.run("Profile created", onDone = done) {
                    session.api.createProfile(choice.name, null)
                }
                is NewProfile.Described -> runner.run("Profile generated from your description", onDone = done) {
                    session.api.createProfileFromPrompt(choice.description)
                }
            }
        }
    }
}

sealed interface NewProfile {
    data class FromType(val type: ProfileRef) : NewProfile
    data class Named(val name: String) : NewProfile
    data class Described(val description: String) : NewProfile
}

/** Mirrors the dashboard: pick a ready-made Profile Type, or a Custom one by name or (beta) by description. */
@Composable
private fun NewProfileDialog(busy: Boolean, onDismiss: () -> Unit, onCreate: (NewProfile) -> Unit) {
    val session = LocalSession.current
    val types = rememberLoader { runCatching { session.api.featuredProfiles() }.getOrDefault(emptyList()) }
    var type by remember { mutableStateOf<ProfileRef?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pickType by remember { mutableStateOf(false) }

    val canCreate = type != null || name.isNotBlank() || description.isNotBlank()
    CdDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Create profile") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Profile type", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Spacer(Modifier.height(4.dp))
                CdFieldCard(onClick = { pickType = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(type?.name ?: "Custom", modifier = Modifier.weight(1f))
                        if (types.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Solar.ExpandMore, null)
                    }
                }
                if (type != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Creates a copy of Control D's ${type!!.name} with its filters, services and options. You can rename and change it afterwards.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    CdTextField(
                        name, { name = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                        enabled = description.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = Palette.Outline)
                        Text("OR", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(horizontal = 10.dp))
                        HorizontalDivider(Modifier.weight(1f), color = Palette.Outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("BETA", Palette.Purple)
                        Spacer(Modifier.width(8.dp))
                        Text("Describe your profile", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    CdTextField(
                        description, { description = it },
                        placeholder = { Text("Describe what you want your profile to do (e.g. improve my privacy, block social networks)") },
                        enabled = name.isBlank(),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (description.isNotBlank()) {
                        Text(
                            "Control D's AI builds the profile; this can take up to a minute.",
                            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canCreate && !busy, onClick = {
                val t = type
                onCreate(
                    when {
                        t != null -> NewProfile.FromType(t)
                        description.isNotBlank() -> NewProfile.Described(description.trim())
                        else -> NewProfile.Named(name.trim())
                    }
                )
            }) { BusyLabel("Create", busy) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickType) {
        PickerDialog(
            "Profile type", listOf<ProfileRef?>(null) + types.data.orEmpty(),
            label = { it?.name ?: "Custom" },
            selected = { it?.pk == type?.pk },
            onDismiss = { pickType = false },
        ) { type = it }
    }
}

@Composable
private fun ProfileCard(
    p: Profile,
    endpoints: List<Device>,
    currentDevice: Device?,
    onOpenEndpoint: (Device) -> Unit,
    onClick: () -> Unit,
) {
    val isCurrent = currentDevice != null && (currentDevice.profile?.pk == p.pk || currentDevice.profile2?.pk == p.pk)
    var showEndpoints by remember { mutableStateOf(false) }
    CdCard(
        Modifier.fillMaxWidth().then(
            if (isCurrent) Modifier.border(1.5.dp, Palette.Teal, CdShape) else Modifier
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (isCurrent) {
                            Spacer(Modifier.width(8.dp)); Pill("This device", Palette.Teal)
                        }
                        if (p.isPaused) {
                            Spacer(Modifier.width(8.dp)); Pill("Paused", Palette.Orange)
                        }
                    }
                    Box {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = endpoints.isNotEmpty()) { showEndpoints = true }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${endpoints.size} endpoint${if (endpoints.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                            )
                            if (endpoints.isNotEmpty()) {
                                Icon(
                                    if (showEndpoints) Solar.ExpandLess else Solar.ExpandMore, null,
                                    Modifier.size(16.dp), tint = Palette.Muted,
                                )
                            }
                        }
                        EndpointsMenu(showEndpoints, endpoints, currentDevice, { showEndpoints = false }, onOpenEndpoint)
                    }
                }
                Icon(Solar.ChevronRight, null, tint = Palette.Muted)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(Solar.Filter, "Filters", p.filters + p.externalFilters)
                Stat(Solar.Widget, "Services", p.services)
                Stat(Solar.Rules, "Rules", p.rules)
                Stat(Solar.Settings, "Options", p.options)
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = Palette.Muted)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(4.dp))
        Text("$n", style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
    }
}

@Composable
fun CreateProfileDialog(
    profiles: List<Profile>,
    initialClone: Profile? = null,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialClone?.let { "${it.name} copy" } ?: "") }
    var clone by remember { mutableStateOf(initialClone) }
    var picking by remember { mutableStateOf(false) }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialClone != null) "Clone profile" else "New profile") },
        text = {
            Column {
                CdTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                CdFieldCard(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Start from", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                            Text(clone?.name ?: "Blank profile")
                        }
                        Icon(Solar.ExpandMore, null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = { onCreate(name.trim(), clone?.pk) }) { BusyLabel("Create", busy) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        PickerDialog(
            "Start from",
            listOf<Profile?>(null) + profiles,
            label = { it?.name ?: "Blank profile" },
            selected = { it?.pk == clone?.pk },
            onDismiss = { picking = false },
        ) { clone = it }
    }
}

/** Dashboard-style endpoints dropdown on a profile card. */
@Composable
private fun EndpointsMenu(
    expanded: Boolean,
    endpoints: List<Device>,
    currentDevice: Device?,
    onDismiss: () -> Unit,
    onOpen: (Device) -> Unit,
) {
    var q by remember(expanded) { mutableStateOf("") }
    CdMenu(expanded, onDismiss, modifier = Modifier.widthIn(min = 260.dp)) {
        if (endpoints.size > 4) {
            CdTextField(
                q, { q = it },
                placeholder = { Text("Search endpoints") },
                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            )
        }
        endpoints
            .filter { q.isBlank() || it.name.contains(q, true) }
            .sortedWith(compareByDescending<Device> { it.pk == currentDevice?.pk }.thenBy { it.name.lowercase() })
            .forEach { d ->
                val isCurrent = d.pk == currentDevice?.pk
                CdMenuItem(
                    text = {
                        Column {
                            Text(d.name, fontWeight = FontWeight.SemiBold)
                            val sub = listOfNotNull(
                                "This device".takeIf { isCurrent },
                                "Not configured".takeIf { d.notConfigured },
                                d.clients.takeIf { it > 0 }?.let { "$it client${if (it == 1) "" else "s"}" },
                            )
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) Palette.Teal else Palette.Muted,
                                )
                            }
                        }
                    },
                    leadingIcon = { DeviceIconWithActivity(d) },
                    onClick = { onDismiss(); onOpen(d) },
                )
            }
    }
}
