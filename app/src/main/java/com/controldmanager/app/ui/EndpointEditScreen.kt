@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*
import java.io.ByteArrayOutputStream

/** Route id used for the Create Endpoint form. */
const val NEW_ENDPOINT = "new"

private val analyticsChoices = listOf(0 to "Off", 1 to "Basic", 2 to "Full")
private val statusChoices = listOf(1 to "Active", 2 to "Soft disabled", 3 to "Hard disabled")

/** Editable copy of an endpoint, as on the dashboard's "Edit Endpoint" form. */
private data class EndpointForm(
    val icon: String,
    val name: String,
    val profile: String?,
    val profile2: String?,
    val stats: Int,
    val desc: String,
    val legacy: Boolean,
    val learnIp: Boolean,
    val ddnsExt: Boolean,
    val ddnsExtHost: String,
    val ddns: Boolean,
    val ddnsSubdomain: String,
    val pinOn: Boolean,
    val pin: String,
    val status: Int,
) {
    companion object {
        fun of(d: Device) = EndpointForm(
            icon = d.icon, name = d.name, profile = d.profile?.pk, profile2 = d.profile2?.pk, stats = d.stats,
            desc = d.desc, legacy = d.legacyIpv4Enabled, learnIp = d.learnIp,
            ddnsExt = d.ddnsExtStatus, ddnsExtHost = d.ddnsExtHost, ddns = d.ddnsStatus, ddnsSubdomain = d.ddnsSubdomain,
            pinOn = d.deactivationPin != null, pin = d.deactivationPin.orEmpty(), status = d.status.coerceAtLeast(1),
        )
    }

    /** Only the fields that changed, in PUT /devices/{id} form names. */
    fun diff(o: EndpointForm): List<Pair<String, String>> = buildList {
        if (name != o.name) add("name" to name.trim())
        if (icon != o.icon) add("icon" to icon)
        if (profile != o.profile && profile != null) add("profile_id" to profile)
        if (profile2 != o.profile2) add("profile_id2" to (profile2 ?: "-1"))
        if (stats != o.stats) add("stats" to "$stats")
        if (desc != o.desc) add("desc" to desc.trim())
        if (legacy != o.legacy) add("legacy_ipv4_status" to if (legacy) "1" else "0")
        if (learnIp != o.learnIp) add("learn_ip" to if (learnIp) "1" else "0")
        if (ddnsExt != o.ddnsExt || (ddnsExt && ddnsExtHost != o.ddnsExtHost)) {
            add("ddns_ext_status" to if (ddnsExt) "1" else "0")
            if (ddnsExt) add("ddns_ext_host" to ddnsExtHost.trim())
        }
        if (ddns != o.ddns || (ddns && ddnsSubdomain != o.ddnsSubdomain)) {
            add("ddns_status" to if (ddns) "1" else "0")
            if (ddns) add("ddns_subdomain" to ddnsSubdomain.trim())
        }
        if (pinOn != o.pinOn || (pinOn && pin != o.pin)) add("deactivation_pin" to if (pinOn) pin.trim() else "-1")
        if (status != o.status) add("status" to "$status")
    }

    /** Names entered for creation: the dashboard accepts several, comma or line separated. */
    val names get() = name.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** POST /devices fields for a new endpoint (status and PIN are applied afterwards with PUT). */
    fun createFields(n: String): List<Pair<String, String>> = buildList {
        add("name" to n); add("icon" to icon); add("profile_id" to profile!!)
        profile2?.let { add("profile_id2" to it) }
        add("stats" to "$stats")
        if (desc.isNotBlank()) add("desc" to desc.trim())
        add("legacy_ipv4_status" to if (legacy) "1" else "0")
        add("learn_ip" to if (learnIp) "1" else "0")
        if (ddnsExt) { add("ddns_ext_status" to "1"); add("ddns_ext_host" to ddnsExtHost.trim()) }
        if (ddns) { add("ddns_status" to "1"); add("ddns_subdomain" to ddnsSubdomain.trim()) }
    }

    val valid get() = name.isNotBlank() && profile != null && icon.isNotBlank() &&
        (!ddnsExt || ddnsExtHost.isNotBlank()) && (!ddns || ddnsSubdomain.isNotBlank()) && (!pinOn || pin.length >= 4)
}

@Composable
fun EndpointScreen(nav: NavHostController, id: String) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val runner = rememberRunner()
    val creating = id == NEW_ENDPOINT
    val loader = rememberLoader(id) {
        val d: Device? = if (creating) null
        else session.devicesWithActivity(regionOverride(ctx)).firstOrNull { it.pk == id } ?: throw ApiException("Endpoint not found")
        Triple(d, session.api.profiles(), runCatching { session.deviceTypes() }.getOrDefault(emptyList()))
    }
    val dev = loader.data?.first
    val blank = loader.data?.takeIf { creating }?.let { (_, profiles, _) ->
        // Same defaults as the dashboard: no type chosen yet, first profile, Full analytics.
        EndpointForm(
            icon = "", name = "", profile = profiles.firstOrNull()?.pk, profile2 = null, stats = 2, desc = "",
            legacy = false, learnIp = false, ddnsExt = false, ddnsExtHost = "", ddns = false, ddnsSubdomain = "",
            pinOn = false, pin = "", status = 1,
        )
    }
    var original by remember(dev, blank != null) { mutableStateOf(dev?.let { EndpointForm.of(it) } ?: blank) }
    var form by remember(dev, blank != null) { mutableStateOf(original) }
    var advanced by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Which request is running, so only its own button spins.
    var deleting by remember { mutableStateOf(false) }
    var confirmNuke by remember { mutableStateOf(false) }
    val changes = form?.let { f -> original?.let { f.diff(it) } }.orEmpty()

    // Floating glass Cancel/Delete | Save bar, like the reference.
    GlassActionBar(listOf(
        if (creating) GlassAction("Cancel", { nav.popBackStack() }, enabled = !runner.busy)
        else GlassAction("Delete", { confirmDelete = true }, enabled = dev != null && !runner.busy, destructive = true),
        GlassAction(
            if (creating) "Create" else "Save",
            onClick = {
                                val f = form ?: return@GlassAction
                                if (creating) {
                                    val n = f.names
                                    var last: Device? = null
                                    runner.run(
                                        if (n.size == 1) "Endpoint created" else "${n.size} endpoints created",
                                        onDone = {
                                            nav.popBackStack()
                                            // Like the dashboard, show the new endpoint's resolvers so it can be set up.
                                            last?.takeIf { n.size == 1 }?.let { nav.navigate("resolvers/${it.pk}") }
                                        },
                                    ) {
                                        for (name in n) {
                                            val created = session.api.createDevice(f.createFields(name))
                                            val extra = buildList {
                                                if (f.pinOn) add("deactivation_pin" to f.pin.trim())
                                                if (f.status != 1) add("status" to "${f.status}")
                                            }
                                            if (extra.isNotEmpty()) session.api.modifyDevice(created.pk, extra)
                                            last = created
                                        }
                                    }
                                } else {
                                    runner.run("Endpoint saved", onDone = { loader.reload() }) { session.api.modifyDevice(id, changes); original = f }
                                }
                            },
            enabled = (creating || changes.isNotEmpty()) && form?.valid == true,
            busy = runner.busy && !deleting, primary = true,
        ),
    ))

    Scaffold(
        topBar = { BackTopBar(nav, if (creating) "Create Endpoint" else "Edit Endpoint", subtitle = dev?.name) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { (d, profiles, types) ->
            val f = form ?: return@LoaderBox
            fun update(block: EndpointForm.() -> EndpointForm) { form = f.block() }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = ActionBarSpace), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FieldLabel("Endpoint Type")
                        val typeLabel = types.flatMap { it.icons }.firstOrNull { it.first == f.icon }?.let { typeLabel(it.first, it.second) } ?: f.icon.ifBlank { "Choose Endpoint Type" }
                        SelectRow(typeLabel, leading = if (f.icon.isBlank()) null else ({ DeviceGlyph(f.icon, 20) })) { pick = "type" }
                        FieldLabel(if (creating) "Endpoint Name(s)" else "Endpoint Name")
                        CdTextField(
                            f.name, { v -> update { copy(name = v) } }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(if (creating) "Enter endpoint name(s)" else "Endpoint name") },
                            supportingText = if (creating) ({ Text("Separate with commas to create several at once.") }) else null,
                        )
                        FieldLabel("Enforced Profile")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SelectRow(profiles.firstOrNull { it.pk == f.profile }?.name ?: "Choose") { pick = "profile" }
                            }
                            if (f.profile2 == null) {
                                IconButton(onClick = { pick = "profile2" }) { Icon(Solar.Add, "Add second profile") }
                            }
                        }
                        f.profile2?.let { p2 ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    SelectRow(profiles.firstOrNull { it.pk == p2 }?.name ?: p2) { pick = "profile2" }
                                }
                                IconButton(onClick = { update { copy(profile2 = null) } }) { Icon(Solar.Close, "Remove second profile") }
                            }
                        }
                    }
                }

                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Solar.ShowChart, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Analytics", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pick = "stats" }) {
                                Text(analyticsChoices.firstOrNull { it.first == f.stats }?.second ?: "${f.stats}", color = Palette.Text)
                                Icon(Solar.ExpandMore, null, tint = Palette.Text)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Log DNS activity for reports, troubleshooting, and usage visibility.",
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.weight(1f),
                            )
                            if (!creating) IconButton(onClick = { confirmNuke = true }, enabled = !runner.busy) {
                                Icon(Solar.DeleteSweep, "Delete analytics data", tint = Palette.Muted)
                            }
                        }
                    }
                }

                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row {
                            FieldLabel("Comments")
                            Spacer(Modifier.weight(1f))
                            Text("${255 - f.desc.length} characters remaining (Optional)", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        CdTextField(f.desc, { v -> if (v.length <= 255) update { copy(desc = v) } }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    }
                }

                CdCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Solar.Tuning, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("ADVANCED SETTINGS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Icon(if (advanced) Solar.ExpandLess else Solar.ExpandMore, null)
                        }
                        if (advanced) {
                            AdvancedRow("adv-legacy", "Legacy DNS", "Enable plain DNS resolver IPs for devices that cannot use encrypted DNS.", f.legacy, onChange = {
                                update { copy(legacy = it) }
                            })
                            AdvancedRow("adv-secure", "Authorize by Secure DNS", "Authorize the network after a successful Secure DNS query from this Endpoint.", f.learnIp, onChange = {
                                update { copy(learnIp = it) }
                            })
                            AdvancedRow("adv-ddns", "Authorize by Dynamic DNS", "Authorize the current IP of a hostname you control for this Endpoint.", f.ddnsExt, {
                                update { copy(ddnsExt = it) }
                            }) {
                                CdTextField(
                                    f.ddnsExtHost, { v -> update { copy(ddnsExtHost = v) } },
                                    label = { Text("Hostname, e.g. myhome.ddns.net") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            AdvancedRow("adv-expose", "Expose IP via DNS", "Publish the latest Secure DNS source IP as a hostname for scripts or remote access.", f.ddns, {
                                update { copy(ddns = it) }
                            }) {
                                CdTextField(
                                    f.ddnsSubdomain, { v -> update { copy(ddnsSubdomain = v) } },
                                    label = { Text("Subdomain") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    supportingText = { d?.ddnsHostname?.takeIf { it.isNotBlank() }?.let { Text(it) } },
                                )
                            }
                            AdvancedRow("adv-pin", "Prevent Deactivation", "Require a PIN before Control D can be disabled in supported apps.", f.pinOn, {
                                update { copy(pinOn = it) }
                            }) {
                                CdTextField(
                                    f.pin, { v -> if (v.length <= 12 && v.all(Char::isDigit)) update { copy(pin = v) } },
                                    label = { Text("PIN (at least 4 digits)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                )
                            }
                            GroupDivider()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AdvancedGlyph("adv-status")
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Status", fontWeight = FontWeight.SemiBold)
                                    Text("Pause or disable this Endpoint without deleting its settings.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                                }
                                TextButton(onClick = { pick = "status" }) {
                                    Text(statusChoices.firstOrNull { it.first == f.status }?.second ?: "Status ${f.status}", color = Palette.Text)
                                    Icon(Solar.ExpandMore, null, tint = Palette.Text)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            when (pick) {
                "type" -> EndpointTypePicker(types, f.icon, onDismiss = { pick = null }) { update { copy(icon = it) } }
                "profile" -> PickerDialog("Enforced Profile", profiles, label = { it.name }, selected = { it.pk == f.profile }, onDismiss = { pick = null }) {
                    update { copy(profile = it.pk, profile2 = profile2?.takeIf { p -> p != it.pk }) }
                }
                "profile2" -> PickerDialog(
                    "Second profile", profiles.filter { it.pk != f.profile }, label = { it.name }, selected = { it.pk == f.profile2 }, onDismiss = { pick = null },
                ) { update { copy(profile2 = it.pk) } }
                "stats" -> PickerDialog("Analytics", analyticsChoices, label = { it.second }, selected = { it.first == f.stats }, onDismiss = { pick = null }) {
                    update { copy(stats = it.first) }
                }
                "status" -> PickerDialog("Status", statusChoices, label = { it.second }, selected = { it.first == f.status }, onDismiss = { pick = null }) {
                    update { copy(status = it.first) }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${dev?.name}?",
            "DNS will stop working on any device still using this endpoint's resolvers. This can't be undone.",
            busy = runner.busy && deleting,
            onDismiss = { confirmDelete = false },
        ) {
            deleting = true
            runner.run("Endpoint deleted", onDone = { confirmDelete = false; nav.popBackStack() }) {
                try { session.api.deleteDevice(id) } finally { deleting = false }
            }
        }
    }
    if (confirmNuke) {
        ConfirmDialog(
            "Delete analytics data?", "All logged DNS activity for this endpoint will be permanently removed.",
            onDismiss = { confirmNuke = false },
        ) { runner.run("Analytics data deleted") { session.api.modifyDevice(id, listOf("nuke_analytics" to "1")) } }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.Muted)

@Composable
private fun SelectRow(label: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    CdFieldCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(10.dp)) }
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Solar.ExpandMore, null, tint = Palette.Muted)
        }
    }
}

@Composable
private fun AdvancedRow(
    icon: String,
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    expanded: (@Composable () -> Unit)? = null,
) {
    GroupDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdvancedGlyph(icon)
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CdSwitch(checked, onChange)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
        if (checked && expanded != null) {
            Spacer(Modifier.height(8.dp))
            expanded()
        }
    }
}

// ---------- Resolvers ----------

/** Control D's anycast bootstrap addresses, as shown in the dashboard. */
private const val BOOTSTRAP_V4 = "76.76.2.22"
private const val BOOTSTRAP_V6 = "2606:1a40::22"

/** DNSCrypt "sdns://" stamp for a DoH resolver (protocol 0x02), matching the dashboard's DNS Stamp. */
private fun dohStamp(doh: String): String? = runCatching {
    val url = java.net.URI(doh)
    val out = ByteArrayOutputStream()
    out.write(0x02)
    val props = 7L // DNSSEC, no logs, no filter
    for (i in 0 until 8) out.write(((props shr (8 * i)) and 0xff).toInt())
    fun lp(s: String) { val b = s.toByteArray(); out.write(b.size); out.write(b) }
    lp(BOOTSTRAP_V4)
    out.write(0) // no certificate hashes
    lp(url.host)
    lp(url.rawPath.ifBlank { "/" })
    "sdns://" + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}.getOrNull()

@Composable
fun EndpointResolversScreen(nav: NavHostController, id: String) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val loader = rememberLoader(id) { session.api.devices().firstOrNull { it.pk == id } ?: throw ApiException("Endpoint not found") }
    var help by remember { mutableStateOf(false) }

    GlassActionBar(listOf(
        GlassAction(
            "Help Me Configure", icon = Solar.MenuBook, primary = true,
            enabled = loader.data?.resolvers?.dot != null,
            onClick = {
                val icon = loader.data?.icon.orEmpty()
                // Android endpoints get the Private DNS shortcut; everything else opens its platform's guide.
                if (icon == "mobile-android") help = true
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(setupGuideUrl(icon))))
            },
        ),
    ))

    Scaffold(
        topBar = { BackTopBar(nav, "Endpoint Resolvers", subtitle = loader.data?.name) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { d ->
            val r = d.resolvers
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    DeviceGlyph(d.icon, 22)
                    Spacer(Modifier.width(10.dp))
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                }
                ResolverSectionHeader("SECURE DNS", "ENCRYPTED", Solar.LockKeyhole)
                CdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CopyField("Resolver ID", r?.uid ?: d.pk)
                        r?.doh?.let { CopyField("DNS-over-HTTPS/3", it) }
                        r?.dot?.let { CopyField("DNS-over-TLS/DoQ", it) }
                        FieldLabel("Bootstrap IPs")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { CopyField(null, BOOTSTRAP_V4) }
                            Box(Modifier.weight(1f)) { CopyField(null, BOOTSTRAP_V6) }
                        }
                        r?.doh?.let(::dohStamp)?.let { CopyField("DNS Stamp", it) }
                    }
                }
                if (d.legacyIpv4Enabled || r?.v4?.isNotEmpty() == true) {
                    ResolverSectionHeader("LEGACY DNS", "UNENCRYPTED", Solar.LockOpen)
                    CdCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            r?.v4?.takeIf { it.isNotEmpty() }?.let { v4 ->
                                FieldLabel("IPv4")
                                v4.forEach { CopyField(null, it) }
                            } ?: d.legacyIpv4?.let { CopyField("IPv4", it) }
                            r?.v6?.takeIf { it.isNotEmpty() }?.let { v6 ->
                                FieldLabel("IPv6")
                                v6.forEach { CopyField(null, it) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (help) {
        val dot = loader.data?.resolvers?.dot.orEmpty()
        CdDialog(
            onDismissRequest = { help = false },
            title = { Text("Use on this phone") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android's Private DNS uses DNS-over-TLS:", color = Palette.Muted)
                    Text("1. Open Settings → Network → Private DNS")
                    Text("2. Choose “Private DNS provider hostname”")
                    Text("3. Paste:")
                    Text(dot, fontFamily = FontFamily.Monospace, color = Palette.Teal)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(ctx, "DoT hostname", dot)
                    help = false
                    // There's no public intent for the Private DNS screen; try the known one, then network settings.
                    try {
                        ctx.startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Copy & open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    help = false
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(setupGuideUrl("mobile-android"))))
                }) { Text("Full guide") }
            },
        )
    }
}

@Composable
private fun ResolverSectionHeader(title: String, badge: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Bg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(icon, null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(badge, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CopyField(label: String?, value: String) {
    val ctx = LocalContext.current
    Column {
        label?.let { FieldLabel(it); Spacer(Modifier.height(4.dp)) }
        Row(
            Modifier.fillMaxWidth().clip(CdShape).background(Palette.Bg)
                .clickable { copyToClipboard(ctx, label ?: "Value", value) }
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { copyToClipboard(ctx, label ?: "Value", value) }) {
                Icon(Solar.ContentCopy, "Copy", tint = Palette.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Clients seen behind an endpoint and its authorized (known) IPs, opened from the endpoint row. */
@Composable
fun EndpointAccessScreen(nav: NavHostController, id: String) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val runner = rememberRunner()
    val loader = rememberLoader(id) {
        session.devicesWithActivity(regionOverride(ctx)).firstOrNull { it.pk == id } ?: throw ApiException("Endpoint not found")
    }
    val ips = rememberLoader(id) { session.api.knownIps(id) }
    LiveRefresh(loader)
    LiveRefresh(ips)
    val now = LocalNowSec.current

    Scaffold(
        topBar = { BackTopBar(nav, "Clients & IPs", subtitle = loader.data?.name) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { d ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                SectionHeader("Clients (${d.clientList.size})")
                if (d.clientList.isEmpty()) {
                    CdCard(Modifier.fillMaxWidth()) {
                        Text(
                            "No clients reported. Clients appear when ctrld runs on a router or computer using this endpoint.",
                            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                // Grouped rows, same as the other lists: icon, name, grey details, last seen on the right.
                d.clientList.forEachIndexed { i, c ->
                    GroupRow(
                        c.label, first = i == 0, last = i == d.clientList.lastIndex, fill = Palette.Card,
                        subtitle = listOf(c.ip, c.mac).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                        icon = { Icon(Solar.Devices, null, tint = Palette.Muted) },
                        trailing = activityAge(c.lastActivity, now)?.let { age -> { Text(age, style = MaterialTheme.typography.labelMedium, color = Palette.Muted) } },
                    )
                }

                // Read-only, like the dashboard (no add / remove there).
                SectionHeader("Known IPs")
                val list = ips.data
                when {
                    list == null -> CdCard(Modifier.fillMaxWidth()) {
                        Box(Modifier.padding(16.dp)) {
                            if (ips.error != null) Text(ips.error!!, color = Palette.Red) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    list.isEmpty() -> CdCard(Modifier.fillMaxWidth()) {
                        Text("No IPs seen yet", color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                    else -> list.forEachIndexed { i, ip ->
                        GroupRow(
                            ip.ip, first = i == 0, last = i == list.lastIndex, fill = Palette.Card,
                            subtitle = listOf(ip.city, ip.country, ip.isp).filter { it.isNotBlank() }.joinToString(" · ")
                                .let { if (ip.ts > 0) "$it · ${formatTime(ip.ts)}" else it }.ifBlank { null },
                            icon = { FlagIcon(ip.country.takeIf { it.length == 2 }, 22) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
/** Dashboard-style type list: search box, then each type with its icon and plain name. */
@Composable
private fun EndpointTypePicker(types: List<DeviceTypeGroup>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember(types) { types.flatMap { it.icons }.map { (k, v) -> k to typeLabel(k, v) } }
    val shown = all.filter { q.isBlank() || it.second.contains(q, true) || it.first.contains(q, true) }
    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text("Endpoint Type") },
        text = {
            Column {
                CdTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) }, placeholder = { Text("Search") },
                )
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (key == selected) Palette.CardHigh else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onPick(key); onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { DeviceGlyph(key, 22, Palette.Muted) }
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (key == selected) Icon(Solar.Check, null, tint = Palette.Teal)
                        }
                    }
                    if (shown.isEmpty()) Text("No matching type", color = Palette.Muted, modifier = Modifier.padding(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The API names several entries just "Other"/"Generic …"; the dashboard says which kind. */
fun typeLabel(key: String, label: String): String = when {
    // Only the bare "Other" entries; "Generic Linux" etc. are real names and stay as they are.
    label.trim() != "Other" -> label
    key.startsWith("browser") -> "Other Browser"
    key.startsWith("tv") -> "Other TV"
    key.startsWith("router") -> "Other Router"
    else -> label
}

/** Section icons for the Edit Endpoint settings (assets/device_icons/adv-*.svg). */
@Composable
private fun AdvancedGlyph(name: String) = coil3.compose.AsyncImage(
    model = "file:///android_asset/device_icons/$name.svg", contentDescription = null,
    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.Text), modifier = Modifier.size(20.dp),
)
