@file:OptIn(ExperimentalMaterial3Api::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.controldmanager.app.api.User
import java.util.Locale

private const val RAW = "raw_queries"
private const val AGG = "agg_queries"

/** Same choices as the dashboard (1 month is 33 days, 1 year 368). */
private val rawChoices = listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", 33 to "1 month")
private val aggChoices = listOf(3 to "3 days", 7 to "7 days", 33 to "1 month", 90 to "3 months", 180 to "6 months", 368 to "1 year")

private fun daysLabel(d: Int) = (rawChoices + aggChoices).firstOrNull { it.first == d }?.second ?: "$d days"

private data class Retention(val raw: Int, val agg: Int, val maxRaw: Int, val maxAgg: Int)

/**
 * The dashboard's Account > Options: Storage Region, Home Countries, Raw Logs and Aggregated Analytics
 * retention, Clear Analytics Data. (Theme and onboarding hints are dashboard-only.)
 */
@Composable
fun AccountOptions(user: User?, onUserChanged: () -> Unit) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val uri = LocalUriHandler.current
    val regions = rememberLoader { runCatching { session.api.analyticsRegions() }.getOrDefault(emptyList()) }
    val region = user?.statsRegion
    val retention = rememberLoader(region) {
        region?.let {
            val cur = session.api.retention(it)
            val lim = runCatching { session.api.retentionLimits(it) }.getOrDefault(emptyMap())
            Retention(cur[RAW] ?: 33, cur[AGG] ?: 368, lim[RAW] ?: 33, lim[AGG] ?: 368)
        }
    }
    var pick by remember { mutableStateOf<String?>(null) }
    var moveTo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lower by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun saveRetention(raw: Int, agg: Int) {
        val r = retention.data ?: return
        val changes = buildMap {
            if (raw != r.raw) put(RAW, raw)
            if (agg != r.agg) put(AGG, agg)
        }
        if (changes.isEmpty() || region == null) return
        runner.run("Analytics retention updated", onDone = { retention.reload() }) { session.api.setRetention(region, changes) }
    }
    // Shortening a retention deletes older data, so ask first (as the dashboard does).
    fun requestRetention(raw: Int, agg: Int) {
        val r = retention.data ?: return
        if (raw < r.raw || agg < r.agg) lower = raw to agg else saveRetention(raw, agg)
    }

    SectionHeader("Options")
    CdCard(Modifier.fillMaxWidth()) {
        Column {
            val regionTitle = regions.data?.firstOrNull { it.first == region }?.second
            OptionRow("Storage Region", regionTitle ?: region ?: "-", enabled = user != null) { pick = "region" }
            GroupDivider()
            OptionRow("Home Countries", null, enabled = user != null, value = {
                val codes = user?.safeCountries.orEmpty()
                if (codes.isEmpty()) Text("None", color = Palette.Muted)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    codes.take(3).forEach { FlagIcon(it, 18); Spacer(Modifier.width(4.dp)) }
                    Text(codes.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                }
            }) { pick = "countries" }
            GroupDivider()
            val r = retention.data
            OptionRow("Raw Logs Retention", r?.let { daysLabel(it.raw) } ?: if (retention.loading) "…" else "-", enabled = r != null) { pick = "raw" }
            GroupDivider()
            OptionRow("Aggregated Analytics Retention", r?.let { daysLabel(it.agg) } ?: if (retention.loading) "…" else "-", enabled = r != null) { pick = "agg" }
            GroupDivider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clear Analytics Data", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = region != null && !runner.busy) { Text("Delete", color = Palette.Red) }
            }
            GroupDivider()
            // Deleting the whole account stays on the website, where it asks for your password.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Delete Account", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { uri.openUri("https://controld.com/dashboard/account") }) {
                    Text("On dashboard", color = Palette.Muted); Spacer(Modifier.width(4.dp))
                    Icon(Solar.OpenInNew, null, Modifier.size(16.dp), tint = Palette.Muted)
                }
            }
        }
    }

    when (pick) {
        "region" -> PickerDialog(
            "Storage Region", regions.data.orEmpty(), label = { it.second },
            selected = { it.first == region }, onDismiss = { pick = null },
        ) { if (it.first != region) moveTo = it }
        "countries" -> HomeCountriesSheet(user?.safeCountries.orEmpty(), onDismiss = { pick = null }) { codes ->
            if (codes != user?.safeCountries) runner.run("Home countries saved", onDone = onUserChanged) { session.api.setHomeCountries(codes) }
        }
        "raw" -> retention.data?.let { r ->
            PickerDialog(
                "Raw Logs Retention", rawChoices.filter { it.first <= r.maxRaw }, label = { it.second },
                selected = { it.first == r.raw }, onDismiss = { pick = null },
            ) { (d, _) -> requestRetention(d, maxOf(r.agg, d)) }
        }
        "agg" -> retention.data?.let { r ->
            PickerDialog(
                "Aggregated Analytics Retention", aggChoices.filter { it.first >= r.raw && it.first <= r.maxAgg }, label = { it.second },
                selected = { it.first == r.agg }, onDismiss = { pick = null },
            ) { (d, _) -> requestRetention(r.raw, d) }
        }
    }

    moveTo?.let { (pk, title) ->
        var clearOld by remember { mutableStateOf(false) }
        CdDialog(
            onDismissRequest = { moveTo = null },
            title = { Text("Change storage region?") },
            text = {
                Column {
                    Text("New analytics will be stored in $title. Data already stored in the current region isn't moved.")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.clickable { clearOld = !clearOld }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(clearOld, { clearOld = it })
                        Text("Also delete the data stored in the current region", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = clearOld
                    moveTo = null
                    runner.run("Storage region changed", onDone = onUserChanged) {
                        session.api.setStorageRegion(pk, c)
                        session.forgetRegion()
                    }
                }) { Text("Change") }
            },
            dismissButton = { TextButton(onClick = { moveTo = null }) { Text("Cancel") } },
        )
    }
    lower?.let { (raw, agg) ->
        ConfirmDialog(
            "Shorten retention?", "Data older than the new retention period will be permanently deleted.",
            confirmLabel = "Shorten", onDismiss = { lower = null },
        ) { lower = null; saveRetention(raw, agg) }
    }
    if (confirmClear) {
        var understood by remember { mutableStateOf(false) }
        CdDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Analytics Data") },
            text = {
                Column {
                    Text("This will permanently clear ALL of your stored Analytics data in this region across every Endpoint.")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(understood, { understood = it })
                        Text("I understand that clearing all of my stored Analytics data is permanent and can't be undone.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = understood, onClick = {
                    confirmClear = false
                    runner.run("Analytics data cleared") { session.api.clearAnalytics() }
                }) { Text("Delete", color = if (understood) Palette.Red else Palette.Muted) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionRow(
    title: String, text: String?, enabled: Boolean = true,
    value: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (value != null) value() else Text(text.orEmpty(), color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        Icon(Solar.ExpandMore, null, tint = Palette.Muted)
    }
}

/** Multi-select country list with flags; saved when the sheet closes, like the dashboard's dropdown. */
@Composable
private fun HomeCountriesSheet(current: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(current.toSet()) }
    var q by remember { mutableStateOf("") }
    val all = remember {
        Locale.getISOCountries().map { it to Locale("", it).displayCountry }.sortedBy { it.second }
    }
    val shown = all.filter { q.isBlank() || it.second.contains(q, true) || it.first.equals(q, true) }
    CdSheet(onDismissRequest = { onSave(chosen.toList().sorted()); onDismiss() }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Home Countries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSave(chosen.toList().sorted()); onDismiss() }) { Text("Done") }
            }
            Text("Traffic from these countries counts as Home Country Traffic in Statistics.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            SearchField(q, "Search countries") { q = it }
        }
        // Same look as the location lists: chosen countries first, then A–Z groups of grouped rows.
        val selectedRows = all.filter { it.first in current && it in shown }
        val groups = shown.filter { it.first !in current }.groupBy { it.second.first().uppercaseChar() }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            fun section(key: String, title: String, rows: List<Pair<String, String>>) {
                if (rows.isEmpty()) return
                item(key = "h_$key") { GroupHeader(title) }
                itemsIndexed(rows, key = { _, r -> "${key}_${r.first}" }) { i, (code, name) ->
                    val on = code in chosen
                    GroupRow(
                        name, first = i == 0, last = i == rows.lastIndex, selected = on,
                        icon = { FlagIcon(code, 24) },
                        trailing = if (on) null else ({ Text(code, color = Palette.Muted, style = MaterialTheme.typography.labelSmall) }),
                    ) { chosen = if (on) chosen - code else chosen + code }
                }
            }
            section("sel", "Home countries", selectedRows)
            groups.forEach { (letter, rows) -> section(letter.toString(), letter.toString(), rows) }
        }
    }
}

/** Account page, as the dashboard's Account: details (email and sign-in method) and the options. */
@Composable
fun AccountSettingsScreen(nav: NavHostController) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = rememberLoader { session.api.user() }
    // API tokens can't read the passkey list (dashboard-session only); the account still says whether one exists.
    val passkeys = rememberLoader { runCatching { session.api.passkeys() }.getOrNull() }
    var confirmEnforce by remember { mutableStateOf(false) }
    // Security changes (password, 2FA, passkeys) are done on the website, which re-checks who you are.
    val openDashboard = { openInAppBrowser(ctx, "https://controld.com/dashboard/account") }
    Scaffold(
        topBar = { BackTopBar(nav, "Account") },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(user, Modifier.padding(pad)) { u ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                SectionHeader("Details")
                // Same rows as the dashboard: SSO accounts show only the email; password accounts add
                // Password and Two-Factor Auth, plus Passkey Enforced once a passkey exists.
                val pwd = u.hasPassword
                val keys = passkeys.data.orEmpty()
                val showEnforce = pwd && (u.hasPasskey || keys.isNotEmpty() || u.passkeyEnforced)
                GroupRow(
                    u.email, first = true, last = !pwd, subtitle = "Email", fill = Palette.Card,
                    trailing = {
                        if (u.sso != null) Text(
                            "Authenticated by\n${u.sso.replaceFirstChar { c -> c.uppercase() }}",
                            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        ) else EditChip(openDashboard)
                    },
                )
                if (pwd) {
                    GroupRow("••••••••••", first = false, last = false, subtitle = "Password", fill = Palette.Card, trailing = { EditChip(openDashboard) })
                    GroupRow(
                        if (u.twofa) "Enabled" else "Disabled", first = false, last = !showEnforce, subtitle = "Two-Factor Auth", fill = Palette.Card,
                        trailing = { EditChip(openDashboard) },
                    )
                    if (showEnforce) GroupRow(
                        "Passkey Enforced", first = false, last = true, fill = Palette.Card,
                        subtitle = "Only allow signing in with a passkey.",
                        trailing = {
                            CdSwitch(u.passkeyEnforced, { on ->
                                if (on) confirmEnforce = true
                                else runner.run("Passkey enforcement off", onDone = { user.reload() }) { session.api.setPasskeyEnforced(false) }
                            }, busy = runner.busy)
                        },
                    )

                    SectionHeader("Passkeys")
                    // Listed when readable; otherwise one row saying passkeys exist, managed on the dashboard.
                    if (keys.isEmpty() && u.hasPasskey) GroupRow(
                        "Passkeys set up", first = true, last = false, fill = Palette.Card,
                        subtitle = "View or rename them on the dashboard",
                        icon = { Icon(Solar.Key, null, tint = Palette.Muted) },
                        trailing = { EditChip(openDashboard) },
                    )
                    keys.forEachIndexed { i, k ->
                        GroupRow(
                            k.name, first = i == 0, last = false, fill = Palette.Card,
                            subtitle = k.created?.let { "Created $it" },
                            icon = { Icon(Solar.Key, null, tint = Palette.Muted) },
                            trailing = { EditChip(openDashboard) },
                        )
                    }
                    GroupRow(
                        "Add Passkey", first = keys.isEmpty() && !u.hasPasskey, last = true, fill = Palette.Card,
                        icon = { Icon(Solar.Add, null, tint = Palette.Teal) },
                        trailing = { Icon(Solar.OpenInNew, null, tint = Palette.Muted, modifier = Modifier.size(20.dp)) },
                        onClick = openDashboard,
                    )
                }
                session.orgId?.let {
                    SectionHeader("Organization")
                    GroupRow(it, first = true, last = true, subtitle = "Managing sub-org", fill = Palette.Card)
                }
                AccountOptions(u) { user.reload() }
            }
        }
    }
    if (confirmEnforce) {
        ConfirmDialog(
            "Enforce passkey?",
            "You'll only be able to sign in with a passkey; password sign-in (including in this app) will stop working. The app stays signed in with its API token.",
            confirmLabel = "Enforce", destructive = false, busy = runner.busy,
            onDismiss = { confirmEnforce = false },
        ) {
            runner.run("Passkey enforced", onDone = { confirmEnforce = false; user.reload() }) { session.api.setPasskeyEnforced(true) }
        }
    }
}

@Composable
private fun EditChip(onClick: () -> Unit) = CdButton("Edit", onClick, height = 34.dp, fontSize = 13.sp)

/** Opens a page in a Chrome tab over the app (shares the Chrome sign-in), or the browser as a fallback. */
fun openInAppBrowser(ctx: android.content.Context, url: String) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(android.graphics.Color.parseColor("#080B17")).build(),
            )
            .setShowTitle(true).build()
            .launchUrl(ctx, android.net.Uri.parse(url))
    }.onFailure {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
