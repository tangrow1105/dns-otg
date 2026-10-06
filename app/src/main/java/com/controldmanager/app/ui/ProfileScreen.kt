@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*

private val tabTitles = listOf("Filters", "Services", "Rules", "Profile Options")
private val tabIcons = listOf(Solar.Filter, Solar.Widget, Solar.Rules, Solar.Settings)

/** Profile tabs as the top navigation pill: icons, with the selected tab's name and count. */
@Composable
private fun ProfileTabs(selected: Int, counts: List<Int?>, onSelect: (Int) -> Unit) {
    TopNavTabs(tabIcons, tabTitles, selected, onSelect, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), counts)
}

@Composable
fun ProfileScreen(nav: NavHostController, id: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val profile = rememberLoader(id) {
        val all = session.api.profiles()
        (all.firstOrNull { it.pk == id } ?: throw ApiException("Profile not found")) to all
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val p = profile.data?.first
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runner.run("Profile exported") {
            val json = session.api.exportProfile(id).toString(2)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("Couldn't write the file")
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(
                nav, p?.name ?: "Profile",
                subtitle = p?.let { if (it.isPaused) "Paused until ${formatTime(it.disabledUntil)}" else "Updated ${formatTime(it.updated)}" },
            ) {
                // Same three actions as the dashboard's profile header: clone, delete, export.
                HeaderAction(Solar.CopyAdd, "Clone profile") { dialog = "clone" }
                HeaderAction(Solar.Delete, "Delete profile") { dialog = "delete" }
                HeaderAction(Solar.FileDownload, "Export profile") {
                    exporter.launch("${(p?.name ?: "profile").replace(Regex("[^A-Za-z0-9 _-]"), "")}.json")
                }
                Spacer(Modifier.width(4.dp))
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ProfileTabs(
                selected = tab.coerceAtMost(tabTitles.lastIndex),
                counts = listOf(p?.filters, p?.services, p?.rules, p?.options),
            ) { tab = it }
            when (tab.coerceAtMost(tabTitles.lastIndex)) {
                0 -> FiltersTab(id)
                1 -> ServicesTab(id)
                2 -> RulesTab(nav, id)
                else -> ProfileOptionsTab(id, p) { profile.reload() }
            }
        }
    }

    when (dialog) {
        // Like the dashboard's Duplicate: just confirm; Control D names the copy and we report that name.
        "clone" -> ConfirmDialog(
            "Duplicate ${p?.name}?",
            "Creates a copy of this profile with the same filters, services, rules and options.",
            confirmLabel = "Duplicate", destructive = false, busy = runner.busy,
            onDismiss = { dialog = null },
        ) {
            var created: String? = null
            runner.run(onDone = {
                dialog = null
                showToast(ctx, created?.let { "Cloned to $it" } ?: "Profile duplicated")
            }) { created = session.api.createProfile(p?.name.orEmpty(), id) }
        }

        "delete" -> ConfirmDialog(
            "Delete ${p?.name}?",
            "This can't be undone. Profiles that are still used by an endpoint can't be deleted. Reassign those endpoints first.",
            busy = runner.busy,
            onDismiss = { dialog = null },
        ) {
            runner.run("Profile deleted", onDone = { dialog = null; nav.popBackStack() }) { session.api.deleteProfile(id) }
        }
    }
}

/** Plain header icon, same as the other screens' top-bar actions. */
@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, Modifier.size(22.dp), tint = Palette.Text) }
}

// ---------- Filters ----------

@Composable
private fun FiltersTab(pid: String) {
    var external by rememberSaveable { mutableStateOf(false) }
    Column {
        SlideSelector(
            listOf(SlideOption("Native", Solar.Shield), SlideOption("3rd party", Solar.PlugCircle)),
            if (external) 1 else 0, { external = it == 1 }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (external) ExternalFilters(pid) else NativeFilters(pid)
    }
}

/** Enables/disables a filter, handling filters with modes (levels) such as Relaxed/Balanced/Strict. */
private suspend fun applyFilter(api: ControlDApi, pid: String, f: Filter, enable: Boolean, level: FilterLevel? = null): Filter {
    if (f.levels.isEmpty()) {
        api.setFilter(pid, f.pk, enable)
        return f.copy(status = if (enable) 1 else 0)
    }
    if (!enable) {
        val on = f.levels.filter { it.status == 1 }.map { it.name } + (if (f.status == 1) listOf(f.pk) else emptyList())
        on.distinct().forEach { api.setFilter(pid, it, false) }
        return f.copy(status = 0, levels = f.levels.map { it.copy(status = 0) })
    }
    val target = level ?: f.activeLevel ?: f.levels.first()
    f.levels.filter { it.status == 1 && it.name != target.name }.forEach { api.setFilter(pid, it.name, false) }
    api.setFilter(pid, target.name, true)
    return f.copy(status = 1, levels = f.levels.map { it.copy(status = if (it.name == target.name) 1 else 0) })
}

@Composable
private fun NativeFilters(pid: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val loader = rememberLoader(pid) { session.api.nativeFilters(pid) }
    var open by remember { mutableStateOf<Filter?>(null) }

    fun update(f: Filter, enable: Boolean, level: FilterLevel? = null) = runner.run {
        val updated = applyFilter(session.api, pid, f, enable, level)
        loader.data = loader.data?.map { if (it.pk == f.pk) updated else it }
        if (open?.pk == f.pk) open = updated
    }

    LoaderBox(loader) { filters ->
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(filters, key = { it.pk }) { f ->
                CdCard(Modifier.fillMaxWidth(), onClick = { open = f }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(filterIcon(f.pk, f.name), null, Modifier.size(26.dp), tint = if (f.enabled) Palette.Text else Palette.Muted)
                            Spacer(Modifier.weight(1f))
                            CdSwitch(f.enabled, { update(f, it) }, busy = runner.busy)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (f.enabled) "Blocked" + (f.activeLevel?.let { " · ${it.title}" } ?: "") else "Allowed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (f.enabled) Palette.Red else Palette.Muted,
                        )
                    }
                }
            }
        }
    }

    open?.let { f ->
        CdSheet(onDismissRequest = { open = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(filterIcon(f.pk, f.name), null, Modifier.size(28.dp), tint = Palette.Text)
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    CdSwitch(f.enabled, { update(f, it) }, busy = runner.busy)
                }
                Spacer(Modifier.height(12.dp))
                Text(html(f.description), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                if (f.levels.isNotEmpty()) {
                    SectionHeader("Mode")
                    SlideSelector(
                        f.levels.map { SlideOption(it.title) },
                        if (f.enabled) f.levels.indexOfFirst { it.status == 1 } else -1,
                        { i -> update(f, true, f.levels[i]) },
                        enabled = !runner.busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExternalFilters(pid: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val loader = rememberLoader(pid) { session.api.externalFilters(pid) }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search 3rd party filters", collapsed = true)

    LoaderBox(loader) { filters ->
        val list = filters.filter { q.isBlank() || it.name.contains(q, true) }.sortedByDescending { it.enabled }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, SearchPillSpace), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item { EmptyState(Solar.Filter, "No filters found") }
            items(list, key = { it.pk }) { f ->
                CdCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold)
                            if (f.description.isNotBlank()) Text(
                                html(f.description), style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CdSwitch(f.enabled, { on ->
                            runner.run {
                                session.api.setFilter(pid, f.pk, on)
                                loader.data = loader.data?.map { if (it.pk == f.pk) it.copy(status = if (on) 1 else 0) else it }
                            }
                        }, busy = runner.busy)
                    }
                }
            }
        }
    }
}

/** Icon for a native filter (mode ids like ads_medium share their parent's icon); the name catches ids we don't know. */
fun filterIcon(pk: String, name: String = ""): ImageVector = when {
    pk.startsWith("ads") -> Solar.Eye
    pk.startsWith("porn") -> Solar.Incognito
    pk.startsWith("ai") || pk.contains("llm") || name.contains("Artificial Intelligence", true) -> Solar.Stars
    pk.startsWith("fakenews") -> Solar.DocumentText
    pk.startsWith("crypto") -> Solar.CpuBolt
    pk.startsWith("dating") -> Solar.Hearts
    pk.startsWith("drugs") -> Solar.Pills
    pk.startsWith("ddns") -> Solar.Global
    pk.startsWith("filehost") -> Solar.CloudUpload
    pk.startsWith("gambling") -> Solar.MoneyBag
    pk.startsWith("games") -> Solar.Gamepad
    pk.startsWith("gov") -> Solar.Buildings
    pk.startsWith("iot") -> Solar.SmartHome
    pk.contains("malware") -> Solar.Bug
    pk.startsWith("nrd") -> Solar.CalendarAdd
    pk.startsWith("typo") -> Solar.DangerTriangle
    pk.startsWith("social") -> Solar.UsersGroup
    pk.startsWith("torrent") -> Solar.Download
    pk.startsWith("urlshort") -> Solar.Link
    pk.startsWith("dnsvpn") -> Solar.ShieldNetwork
    else -> Solar.Filter
}

/** Icon for a profile option, by its id. */
private fun optionIcon(pk: String): ImageVector = when {
    pk == "ai_malware" -> Solar.ShieldStar
    pk.contains("attack") -> Solar.ShieldWarning
    pk.contains("rfc1918") -> Solar.HomeWifi
    pk.contains("dnssec") -> Solar.LockUnlocked
    pk.startsWith("ttl") -> Solar.Stopwatch
    pk == "b_resp" -> Solar.Forbidden
    pk.contains("ecs") -> Solar.MapPoint
    pk.contains("spoof") -> Solar.Routing
    pk.contains("dns64") -> Solar.Transfer
    pk == "cflat" -> Solar.Layers
    pk == "safesearch" -> Solar.MagnifierCheck
    pk.contains("youtube") -> Solar.PlayCircle
    else -> Solar.Tuning
}

fun html(s: String): String = HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

@Composable
fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    CdTextField(
        value, onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Solar.Close, "Clear") } },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------- Profile options (mirrors dashboard/profiles/{id}/profile-options) ----------

private data class OptionsData(
    val options: List<ProfileOption>,
    val restrictions: List<Restriction>,
    val enabledRestrictions: Set<String>,
    val defaultRule: Action?,
    val proxies: List<Proxy>,
)

/** Option order on dashboard/profiles/{id}/profile-options. */
private val dashboardOptionOrder = listOf(
    "ai_malware", "block_attacks", "block_rfc1918", "no_dnssec", "ttl_blck", "ttl_spff", "ttl_pass",
    "b_resp", "ecs_subnet", "spoof_ipv6", "dns64", "cflat", "safesearch", "safeyoutube",
)

private val disableDurations = listOf(
    "5 minutes" to 5 * 60L,
    "15 minutes" to 15 * 60L,
    "30 minutes" to 30 * 60L,
    "1 hour" to 3600L,
    "2 hours" to 2 * 3600L,
    "4 hours" to 4 * 3600L,
    "8 hours" to 8 * 3600L,
    "24 hours" to 24 * 3600L,
)

@Composable
private fun OptionCard(
    iconPk: String,
    fallback: ImageVector,
    title: String,
    description: String?,
    infoUrl: String?,
    control: @Composable () -> Unit = {},
    below: (@Composable () -> Unit)? = null,
    first: Boolean = true,
    last: Boolean = true,
) {
    // A row of a grouped settings box (same style as the Account and Billing pages).
    Column(Modifier.fillMaxWidth().clip(groupShape(first, last)).background(Palette.Card)) {
        if (!first) GroupDivider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(fallback, null, Modifier.size(24.dp), tint = Palette.Text)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (!description.isNullOrBlank() || infoUrl != null) {
                        Text(
                            buildAnnotatedString {
                                description?.takeIf { it.isNotBlank() }?.let { append(it); append(" ") }
                                infoUrl?.let { url ->
                                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                                        append("Learn more")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                        )
                    }
                }
                control()
            }
            below?.let {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(start = 36.dp)) { it() }
            }
        }
    }
}

@Composable
private fun SelectBox(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    CdFieldCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Solar.ExpandMore, null, tint = Palette.Muted)
        }
    }
}

@Composable
private fun ProfileOptionsTab(pid: String, profile: Profile?, onChanged: () -> Unit) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val loader = rememberLoader(pid) {
        val options = session.options()
        val available = runCatching { session.api.availableRestrictions() }.getOrDefault(emptyList())
        val enabled = if (available.isEmpty()) emptySet() else runCatching { session.api.enabledRestrictions(pid) }.getOrDefault(emptySet())
        OptionsData(
            options, available, enabled,
            runCatching { session.api.defaultRule(pid) }.getOrNull(),
            runCatching { session.proxies() }.getOrDefault(emptyList()),
        )
    }
    // Local overlays so controls react instantly while the profile reloads.
    var values by remember(profile) { mutableStateOf(profile?.optionValues.orEmpty()) }
    var restrictionsOn by remember(loader.data) { mutableStateOf(loader.data?.enabledRestrictions.orEmpty()) }
    var defaultRule by remember(loader.data) { mutableStateOf(loader.data?.defaultRule) }
    var editing by remember { mutableStateOf<ProfileOption?>(null) }
    var choosing by remember { mutableStateOf<ProfileOption?>(null) }
    var pickRedirect by remember { mutableStateOf(false) }
    var autoStarted by remember { mutableStateOf(false) }
    var pickDuration by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(disableDurations.first()) }
    var unlocking by remember { mutableStateOf(false) }

    fun saveDefault(a: Action) = runner.run("Default rule: ${Do.label(a.doType)}") {
        session.api.setDefaultRule(pid, a)
        defaultRule = a
    }

    LoaderBox(loader) { data ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)) {
            // Profile name
            item {
                var name by remember(profile?.name) { mutableStateOf(profile?.name.orEmpty()) }
                // What the server has now: updated as soon as the rename lands, before the reload.
                var savedName by remember(profile?.name) { mutableStateOf(profile?.name.orEmpty()) }
                var renaming by remember { mutableStateOf(false) }
                LaunchedEffect(runner.busy) { if (!runner.busy) renaming = false }
                Column(Modifier.fillMaxWidth().clip(groupShape(true, false)).background(Palette.Card)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Solar.UserId, null, Modifier.size(24.dp), tint = Palette.Text)
                        Spacer(Modifier.width(12.dp))
                        CdTextField(
                            name, { name = it },
                            label = { Text("Profile Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            trailingIcon = {
                                if (renaming) {
                                    // Spinner in the tick's place so the tap visibly did something.
                                    CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Teal, strokeWidth = 2.5.dp)
                                } else if (name.isNotBlank() && name.trim() != savedName) {
                                    IconButton(onClick = {
                                        val newName = name.trim()
                                        renaming = true
                                        runner.run("Renamed", onDone = { savedName = newName; onChanged() }) { session.api.renameProfile(pid, newName) }
                                    }, enabled = !runner.busy) { Icon(Solar.Check, "Save", tint = Palette.Teal) }
                                }
                            },
                        )
                    }
                }
            }
            // Default rule
            item {
                val d = defaultRule?.doType ?: Do.BYPASS
                val via = defaultRule?.via?.let { v ->
                    if (v == ControlDApi.AUTO_LOCATION) "Auto location" else data.proxies.firstOrNull { it.pk == v }?.label ?: v
                }
                OptionCard(
                    "default-rule", Solar.Flag, "Default Rule",
                    "Applies to all your Internet activity not affected by a Filter, Service or Custom Rule.",
                    "https://docs.controld.com/docs/default-rule",
                    first = false,
                    below = {
                        Column {
                            Text(
                                when (d) {
                                    Do.BLOCK -> "Blocking all Internet Activity"
                                    Do.REDIRECT -> "Redirecting all Internet Activity" + (via?.let { " via $it" } ?: "")
                                    Do.SPOOF -> "Spoofing all Internet Activity" + (via?.let { " to $it" } ?: "")
                                    else -> "Bypassing all Internet Activity"
                                },
                                color = actionColor(d), fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            QuickActions(selected = d, active = true, busy = runner.busy) { pick ->
                                when {
                                    // Already redirecting: just open the Default Location panel.
                                    pick == Do.REDIRECT && d == Do.REDIRECT -> pickRedirect = true
                                    // Switching to Redirect starts on Auto (like the dashboard) and shows the panel.
                                    pick == Do.REDIRECT -> {
                                        saveDefault(Action(Do.REDIRECT, ControlDApi.AUTO_LOCATION))
                                        autoStarted = true
                                        pickRedirect = true
                                    }
                                    else -> saveDefault(Action(pick))
                                }
                            }
                        }
                    },
                )
            }

            item { SectionHeader("Options") }
            // Same order as the dashboard; a restriction that duplicates an option (e.g. Safe Search) is shown once, as the option.
            val shownOptions = data.options
                .sortedBy { o -> dashboardOptionOrder.indexOf(o.pk).let { if (it < 0) Int.MAX_VALUE else it } }
            val optionKeys = data.options.flatMap { listOf(it.pk.lowercase(), it.title.lowercase()) }.toSet()
            val shownRestrictions = data.restrictions.filterNot { it.pk.lowercase() in optionKeys || it.name.lowercase() in optionKeys }
            items(shownOptions, key = { "o_" + it.pk }) { o ->
                val firstRow = o == shownOptions.firstOrNull()
                val value = values[o.pk]
                val on = value != null && value != "0"
                val hasChoices = o.choices.isNotEmpty()
                OptionCard(
                    o.pk, optionIcon(o.pk), o.title, o.description, o.infoUrl,
                    first = firstRow, last = false,
                    control = {
                        CdSwitch(on, { checked ->
                            when {
                                !checked -> runner.run(onDone = onChanged) {
                                    session.api.setOption(pid, o.pk, false)
                                    values = values - o.pk
                                }
                                hasChoices -> {
                                    val v = o.choices.first().first
                                    runner.run(onDone = onChanged) {
                                        session.api.setOption(pid, o.pk, true, v)
                                        values = values + (o.pk to v)
                                    }
                                }
                                o.type == "toggle" -> runner.run(onDone = onChanged) {
                                    session.api.setOption(pid, o.pk, true, "1")
                                    values = values + (o.pk to "1")
                                }
                                else -> editing = o
                            }
                        }, busy = runner.busy)
                    },
                    below = when {
                        hasChoices && on -> ({
                            val label = o.choices.firstOrNull { it.first == value }?.second ?: o.choices.first().second
                            SelectBox(label, enabled = !runner.busy) { choosing = o }
                        })
                        o.type != "toggle" && on -> ({
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Value: $value", color = Palette.Teal, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { editing = o }) { Text("Edit") }
                            }
                        })
                        else -> null
                    },
                )
            }
            items(shownRestrictions, key = { "r_" + it.pk }) { r ->
                val on = r.pk in restrictionsOn
                OptionCard(r.pk, Solar.ShieldCheck, r.name, r.description, null, first = shownOptions.isEmpty() && r == shownRestrictions.firstOrNull(), last = false, control = {
                    CdSwitch(on, { checked ->
                        runner.run {
                            session.api.setRestriction(pid, r.pk, checked)
                            restrictionsOn = if (checked) restrictionsOn + r.pk else restrictionsOn - r.pk
                        }
                    }, busy = runner.busy)
                })
            }
            // Disable (pause)
            item {
                val paused = profile?.isPaused == true
                OptionCard(
                    "disable", Solar.PauseCircle, "Disable", "Temporarily disable all filters, services and rules.", null,
                    first = shownOptions.isEmpty() && shownRestrictions.isEmpty(), last = false,
                    control = {
                        CdSwitch(paused, { checked ->
                            if (checked) runner.run("Disabled for ${duration.first}", onDone = onChanged) {
                                session.api.pauseProfile(pid, System.currentTimeMillis() / 1000 + duration.second)
                            } else runner.run("Profile re-enabled", onDone = onChanged) { session.api.pauseProfile(pid, 0) }
                        }, busy = runner.busy)
                    },
                    below = {
                        if (paused) Text("Disabled until ${formatTime(profile!!.disabledUntil)}", color = Palette.Orange, style = MaterialTheme.typography.labelLarge)
                        else SelectBox(duration.first, enabled = !runner.busy) { pickDuration = true }
                    },
                )
            }
            // Lock profile
            item {
                val locked = profile?.locked == true
                var message by remember(profile?.lockMessage) { mutableStateOf(profile?.lockMessage.orEmpty()) }
                OptionCard(
                    "profile-lock", Solar.LockKeyhole, "Lock Profile",
                    "Lock the profile to be read only and display a message of your choice when you try to modify it.",
                    "https://docs.controld.com/docs/profile-options",
                    first = false,
                    control = {
                        CdSwitch(locked, { checked ->
                            if (checked) runner.run("Profile locked", onDone = onChanged) { session.api.lockProfile(pid, message.trim()) }
                            else unlocking = true
                        }, busy = runner.busy)
                    },
                    below = {
                        CdTextField(
                            message, { message = it },
                            placeholder = { Text("Enter message") },
                            singleLine = true, enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        if (pickRedirect) {
            DefaultLocationDialog(
                profileId = pid,
                current = defaultRule,
                proxies = data.proxies,
                busy = runner.busy,
                startedAuto = autoStarted,
                onDismiss = { pickRedirect = false; autoStarted = false },
            ) { a -> saveDefault(a) }
        }
    }

    choosing?.let { o ->
        PickerDialog(
            o.title, o.choices, label = { it.second }, selected = { it.first == values[o.pk] },
            onDismiss = { choosing = null },
        ) { (v, _) ->
            runner.run(onDone = onChanged) {
                session.api.setOption(pid, o.pk, true, v)
                values = values + (o.pk to v)
            }
        }
    }
    editing?.let { o ->
        NumberOrTextDialog(o, values[o.pk] ?: o.defaultValue?.toString().orEmpty(), onDismiss = { editing = null }) { v ->
            runner.run(onDone = onChanged) {
                session.api.setOption(pid, o.pk, true, v)
                values = values + (o.pk to v)
            }
        }
    }
    if (pickDuration) {
        PickerDialog("Disable for", disableDurations, label = { it.first }, selected = { it == duration }, onDismiss = { pickDuration = false }) {
            duration = it
        }
    }
    if (unlocking) UnlockDialog(onDismiss = { unlocking = false }) { password ->
        runner.run("Profile unlocked", onDone = onChanged) { session.api.unlockProfile(pid, password) }
    }
}

@Composable
private fun UnlockDialog(onDismiss: () -> Unit, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock profile") },
        text = {
            Column {
                Text("Control D asks for your account password to unlock a profile.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(10.dp))
                CdTextField(
                    pw, { pw = it }, label = { Text("Account password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onUnlock(pw); onDismiss() }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberOrTextDialog(o: ProfileOption, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val numeric = o.defaultValue is Number
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(o.title) },
        text = {
            Column {
                Text(o.description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Spacer(Modifier.height(10.dp))
                CdTextField(
                    v, { v = it }, singleLine = true, label = { Text("Value") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onSave(v.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Mirrors the dashboard's "Default Location" panel: original IP, Auto/Manual redirect, and the
 * IPv4/IPv6 that websites see after the change (re-checked once the save lands).
 */
@Composable
private fun DefaultLocationDialog(
    profileId: String,
    current: Action?,
    proxies: List<Proxy>,
    busy: Boolean,
    /** True when opened right after the card switched the rule to Auto (that save is still running). */
    startedAuto: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Action) -> Unit,
) {
    val session = LocalSession.current
    val redirecting = current?.doType == Do.REDIRECT && current.enabled
    val manualVia = current?.via?.takeIf { redirecting && it != ControlDApi.AUTO_LOCATION }
    var manual by remember { mutableStateOf(manualVia != null) }
    var q by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(startedAuto) }
    var savingPk by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableIntStateOf(0) }
    val original = rememberLoader { session.api.ip() }
    // The new IP only shows on this phone when its endpoint uses this profile (as either profile).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val usage = rememberLoader {
        val devices = session.api.devices()
        val here = session.currentEndpointId(ctx, devices)?.let { id -> devices.firstOrNull { it.pk == id } }
        when {
            here == null -> ProfileUse.NOT_ON_CONTROL_D
            here.profile?.pk == profileId || here.profile2?.pk == profileId -> ProfileUse.THIS_PROFILE
            else -> ProfileUse.OTHER_PROFILE
        }
    }
    val visible = rememberLoader(check) {
        // Give the new rule a moment to apply before asking which IP the internet sees.
        if (check > 0) kotlinx.coroutines.delay(800)
        session.api.visibleIps()
    }
    LaunchedEffect(busy) { if (!busy && saving) { saving = false; check++ } }

    fun save(a: Action) { saving = true; onSave(a) }

    val autoOn = redirecting && current?.via == ControlDApi.AUTO_LOCATION

    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default Location") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Mask your IP address when you browse the Internet. Sites that you visit will no longer see your actual IP address.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                IpRow("Original IP", original.data?.ip, original.loading)
                Spacer(Modifier.height(14.dp))
                // Nothing is selected until the Default Rule actually redirects.
                SlideSelector(
                    listOf(
                        SlideOption(if (saving && savingPk == null && !manual) "Saving…" else "Auto", Solar.AutoMode),
                        SlideOption("Manual", Solar.TouchApp),
                    ),
                    when { manual -> 1; redirecting || saving -> 0; else -> -1 },
                    { i ->
                        if (i == 1) manual = true
                        else {
                            manual = false
                            if (!autoOn) { savingPk = null; save(Action(Do.REDIRECT, ControlDApi.AUTO_LOCATION)) }
                        }
                    },
                    enabled = !busy,
                )
                if (manual) {
                    // Manual means "pick a location", so the list is shown straight away.
                    Spacer(Modifier.height(10.dp))
                    CdTextField(
                        q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) }, placeholder = { Text("Search city or country") },
                    )
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        LocationGroups(proxies, q, manualVia, busyPk = savingPk.takeIf { saving }, enabled = !busy) { p ->
                            if (p.pk != manualVia) { savingPk = p.pk; save(Action(Do.REDIRECT, p.pk)) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val v = visible.data
                val checking = visible.loading || saving
                val use = usage.data
                if (use == ProfileUse.OTHER_PROFILE || use == ProfileUse.NOT_ON_CONTROL_D) {
                    // Same warning as the dashboard: the IPs here would describe a different profile (or none).
                    LocationWarning(
                        if (use == ProfileUse.OTHER_PROFILE)
                            "You are changing Default Location settings for a Profile that you're not currently using on this Endpoint. You won't be able to see your new IP."
                        else
                            "This phone isn't using Control D right now, so you won't be able to see your new IP."
                    )
                } else {
                    IpRow("New IPv4", v?.first?.ip, checking, unavailable = !checking && v?.first == null)
                    Spacer(Modifier.height(6.dp))
                    IpRow("New IPv6", v?.second?.ip, checking, unavailable = !checking && v?.second == null)
                }
                if (!redirecting && !checking) {
                    Text(
                        "Default Rule isn't redirecting yet. Choose Auto or a location.",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private enum class ProfileUse { THIS_PROFILE, OTHER_PROFILE, NOT_ON_CONTROL_D }

@Composable
private fun LocationWarning(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Bg).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Solar.DangerTriangle, null, tint = Palette.Orange, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun IpRow(label: String, value: String?, loading: Boolean, unavailable: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        when {
            loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            unavailable || value.isNullOrBlank() -> Text("Unavailable", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            else -> Text(value, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
