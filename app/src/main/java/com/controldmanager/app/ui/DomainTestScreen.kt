package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*
import kotlinx.coroutines.launch

/** Names the dashboard hard-codes for keys that aren't in the service / filter lists. */
private val specialNames = mapOf(
    "ip_malware" to "Malware IP", "ai_malware" to "AI Malware", "csam" to "CSAM", "terrorism" to "Terrorism",
    "attacks" to "Block DNS Exfiltration", "safesearch" to "SafeSearch", "safeyoutube" to "Restricted Youtube",
)

private val rcodeNames = mapOf(0 to "NOERROR", 1 to "FORMERR", 2 to "SERVFAIL", 3 to "NXDOMAIN", 4 to "NOTIMP", 5 to "REFUSED")

/** "https://www.Example.com/path?x" → "www.example.com". */
private fun normalizeDomain(input: String): String =
    input.trim().lowercase().replace(Regex("^[a-z]+://"), "").split('/', '?', '#', ':').first().trimEnd('.')

/**
 * The dashboard's Domain Test: asks an endpoint's own resolver how it answers a domain, and why
 * (blocked, bypassed or redirected, and by which filter, service or rule). Test queries aren't logged.
 */
@Composable
fun DomainTestScreen(nav: NavHostController) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var domain by rememberSaveable { mutableStateOf("") }
    var endpointId by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var pickEndpoint by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DomainTestResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val devices = rememberLoader { session.api.devices().filter { !it.resolvers?.uid.isNullOrBlank() } }
    val services = rememberLoader { runCatching { session.allServices() }.getOrDefault(emptyMap()) }
    val filterNames = rememberLoader { runCatching { session.filterNames() }.getOrDefault(emptyMap()) }
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    // Start on the endpoint this phone uses, when there is one.
    LaunchedEffect(devices.data) {
        val list = devices.data ?: return@LaunchedEffect
        if (endpointId == null || list.none { it.pk == endpointId }) {
            endpointId = runCatching { session.currentEndpointId(ctx, list) }.getOrNull() ?: list.firstOrNull()?.pk
        }
    }
    val endpoint = devices.data?.firstOrNull { it.pk == endpointId }
    val name = normalizeDomain(domain)
    val canCheck = name.isNotEmpty() && endpoint != null && !busy

    fun check() {
        val uid = endpoint?.resolvers?.uid ?: return
        if (name.isEmpty()) return
        focus.clearFocus()
        busy = true; error = null
        scope.launch {
            try { result = session.api.domainTest(uid, name, if (type == 0) "A" else "AAAA") }
            catch (e: Exception) { result = null; error = e.message ?: "Couldn't reach Control D" }
            finally { busy = false }
        }
    }

    Scaffold(
        topBar = { BackTopBar(nav, "Domain Test", "Is a domain blocked, bypassed or redirected?") },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CdTextField(
                domain, { domain = it },
                placeholder = { Text("Enter domain, e.g. tiktok.com") },
                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null) },
                trailingIcon = { if (domain.isNotEmpty()) IconButton(onClick = { domain = ""; result = null; error = null }) { Icon(Solar.Close, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canCheck) check() }),
                modifier = Modifier.fillMaxWidth(),
            )
            // Endpoint picker (the test runs against that endpoint's profiles).
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(FieldFill)
                    .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable(enabled = devices.data != null) { pickEndpoint = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (endpoint != null) DeviceGlyph(endpoint.icon, 20) else Icon(Solar.Devices, null, Modifier.size(20.dp), tint = Palette.Muted)
                Spacer(Modifier.width(10.dp))
                Text(
                    endpoint?.name ?: if (devices.loading) "Loading endpoints…" else "Choose endpoint",
                    color = if (endpoint != null) Palette.Text else Palette.Muted, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Icon(Solar.ExpandMore, null, Modifier.size(18.dp), tint = Palette.Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlideSelector(listOf(SlideOption("A", null), SlideOption("AAAA", null)), type, { type = it }, Modifier.weight(1f), height = 48.dp)
                CdButton("Check", { check() }, Modifier.weight(1f), icon = Solar.MagnifierCheck, enabled = canCheck, busy = busy, tint = Palette.Teal, height = 48.dp)
            }

            error?.let { ErrorNote(it) }
            result?.let { r ->
                VerdictCard(r, services.data.orEmpty(), filterNames.data.orEmpty(), proxies.data.orEmpty())
                ResultsCard(r)
            }
            if (result == null && error == null) {
                Text(
                    "Checks how the chosen endpoint answers a domain right now, and why. Test queries don't appear in your Activity log or statistics.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }

    if (pickEndpoint) {
        PickerDialog(
            "Endpoint", devices.data.orEmpty(), label = { it.name }, selected = { it.pk == endpointId },
            onDismiss = { pickEndpoint = false },
        ) { endpointId = it.pk; result = null; error = null }
    }
}

@Composable
private fun ErrorNote(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Solar.DangerTriangle, null, Modifier.size(20.dp), tint = Palette.Orange)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private val TestBlocked = Color(0xFFE93349)
private val TestBypassed get() = Palette.mix(Color(0xFF1BE3AD), Color(0xFF0FA678))
private val TestRedirected get() = Palette.mix(Color(0xFFE69201), Color(0xFFD08400))

@Composable
private fun VerdictCard(r: DomainTestResult, services: Map<String, Service>, filters: Map<String, String>, proxies: List<Proxy>) {
    val v = r.verdict
    if (v == null) {
        // Nothing matched: the domain resolves normally (the dashboard shows only the results here).
        Row(
            Modifier.fillMaxWidth().clip(CdShape).background(Palette.Card).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Solar.CheckCircle, null, Modifier.size(20.dp), tint = TestBypassed)
            Spacer(Modifier.width(10.dp))
            Text("No rule matched: ${r.domain} resolves normally", color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val (label, color) = when (v.action) {
        "0" -> "Blocked" to TestBlocked
        "1" -> "Bypassed" to TestBypassed
        "2", "3" -> "Redirected" to TestRedirected
        else -> "Error" to TestBlocked
    }
    val blocked = v.action == "0"
    Column(
        Modifier.fillMaxWidth().clip(CdShape)
            .background(if (blocked) TestBlocked.copy(alpha = 0.14f) else Palette.Card)
            .then(if (blocked) Modifier.border(1.dp, TestBlocked.copy(alpha = 0.3f), CdShape) else Modifier),
    ) {
        TestRow("Action") { Text(label, color = color, fontWeight = FontWeight.SemiBold) }
        GroupDivider()
        TestRow("Reason") { ReasonValue(v, services, filters) }
        v.via?.let { code ->
            GroupDivider()
            val p = proxies.firstOrNull { it.pk == code }
            TestRow("Redirected location") {
                FlagIcon(p?.country, 18)
                Spacer(Modifier.width(8.dp))
                Text(p?.label ?: code, color = Palette.Muted, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun ReasonValue(v: DomainVerdict, services: Map<String, Service>, filters: Map<String, String>) {
    val m = v.match
    val pretty = m.replaceFirstChar { it.uppercase() }
    when (v.source) {
        "svc" -> {
            val name = specialNames[m] ?: services[m]?.name ?: pretty
            ServiceIcon(m, name, Palette.Muted, 22)
            Spacer(Modifier.width(8.dp))
            Text(name, color = Palette.Muted)
        }
        "filter", "bl" -> {
            val name = specialNames[m] ?: filters[m] ?: pretty
            Icon(filterIcon(m, name), null, Modifier.size(18.dp), tint = Palette.Muted)
            Spacer(Modifier.width(8.dp))
            Text(name, color = Palette.Muted)
        }
        else -> {
            val (icon, name) = when (v.source) {
                "rules" -> Solar.Rules to m
                "default" -> Solar.Flag to "Default Rule"
                "rebind" -> Solar.HomeWifi to "DNS Rebind Protection"
                "grules" -> Solar.Global to "Global Rule"
                else -> Solar.Global to (specialNames[m] ?: pretty)
            }
            Icon(icon, null, Modifier.size(18.dp), tint = Palette.Muted)
            Spacer(Modifier.width(8.dp))
            Text(name, color = Palette.Muted, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun TestRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = value)
    }
}

@Composable
private fun ResultsCard(r: DomainTestResult) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(CdShape).background(Palette.Card)) {
        Text("Results", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().background(Palette.Ink.copy(alpha = 0.04f)).padding(horizontal = 16.dp, vertical = 12.dp))
        TestRow("RCODE") { Text(rcodeNames[r.rcode] ?: "${r.rcode}", color = Palette.Muted) }
        GroupDivider()
        TestRow("Flags") {
            if (r.flags.isEmpty()) Text("None", color = Palette.Muted)
            r.flags.forEach { Text(it, color = Palette.Muted, modifier = Modifier.padding(start = 12.dp)) }
        }
        if (r.answers.isNotEmpty()) {
            GroupDivider()
            val first = r.answers.first()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.domain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${first.ttl}", color = Palette.Muted, fontSize = 14.sp)
                Spacer(Modifier.width(14.dp))
                Text(first.type, color = Palette.Muted, fontSize = 14.sp)
            }
            r.answers.forEach { a ->
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { copyToClipboard(ctx, a.type, a.data) }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.data, color = Palette.Muted, modifier = Modifier.weight(1f))
                    if (a.type != first.type) Text(a.type, color = Palette.Muted.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }
    }
}
