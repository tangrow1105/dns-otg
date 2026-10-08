@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
    var reporting by remember { mutableStateOf<Boolean?>(null) }   // true = false positive, false = false negative

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
                ReportButtons(r) { reporting = it }
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

    val rep = reporting
    val res = result
    if (rep != null && res != null) {
        ReportDialog(rep, res, services.data.orEmpty(), filterNames.data.orEmpty()) { reporting = null }
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

// ---------- Report False Positive / False Negative (same rules as the dashboard) ----------

/** Filter list for reports always starts with these two (they aren't regular filters). */
private val reportExtraFilters = listOf("ai_malware" to "AI Malware", "ip_malware" to "Malware IP")
private const val MAX_REPORT_DOMAINS = 50
private const val MAX_REPORT_COMMENT = 300

/** False Positive: only Control D's own filters and services, not 3rd-party lists or the protected categories. */
fun canReportFalsePositive(r: DomainTestResult): Boolean {
    val v = r.verdict ?: return false
    return v.source in setOf("filter", "bl", "svc") && !v.match.startsWith("x-") &&
        v.match !in setOf("safesearch", "csam", "terrorism") && r.domain.isNotBlank()
}

/** False Negative: anything that wasn't blocked, including no rule matched. */
fun canReportFalseNegative(r: DomainTestResult) = r.verdict?.action != "0" && r.domain.isNotBlank()

private data class ParsedDomains(val domains: List<String>, val error: String?)

/** One domain or URL per line: un-defangs (example[.]com, hxxps://), takes the host of full URLs, rejects duplicates. */
private fun parseReportDomains(text: String): ParsedDomains {
    val lines = text.split('\n').mapIndexed { i, v -> (i + 1) to v.trim() }.filter { it.second.isNotEmpty() }
    if (lines.isEmpty()) return ParsedDomains(emptyList(), null)
    if (lines.size > MAX_REPORT_DOMAINS) return ParsedDomains(emptyList(), "Enter no more than $MAX_REPORT_DOMAINS domains or URLs.")
    val seen = mutableSetOf<String>(); val out = mutableListOf<String>()
    for ((line, raw) in lines) {
        var t = java.text.Normalizer.normalize(raw.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), ""), java.text.Normalizer.Form.NFKC)
            .replace("[.]", ".").replace("(.)", ".").replace("<.>", ".")
            .replace(Regex("^hxxp(s?)://", RegexOption.IGNORE_CASE), "http$1://")
        t = runCatching { java.net.URLDecoder.decode(t, "UTF-8") }.getOrDefault(t).trim()
        val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t"
        val host = runCatching { java.net.URI(withScheme).host }.getOrNull()
            ?: runCatching { android.net.Uri.parse(withScheme).host }.getOrNull()
        val ascii = host?.let { runCatching { java.net.IDN.toASCII(it.trimEnd('.')) }.getOrNull() }
        if (ascii == null || !Regex("^(?=.{1,253}$)([a-z0-9-]{1,63}\\.)+[a-z0-9-]{2,63}$", RegexOption.IGNORE_CASE).matches(ascii))
            return ParsedDomains(emptyList(), "Line $line is not a valid domain or URL.")
        if (!seen.add(ascii.lowercase())) return ParsedDomains(emptyList(), "Line $line duplicates another domain.")
        out += java.net.IDN.toUnicode(ascii)
    }
    return ParsedDomains(out, null)
}

@Composable
fun ReportButtons(r: DomainTestResult, onReport: (falsePositive: Boolean) -> Unit) {
    val fp = canReportFalsePositive(r); val fn = canReportFalseNegative(r)
    if (!fp && !fn) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (fp) CdButton("Report False Positive", { onReport(true) }, height = 44.dp, fontSize = 14.sp)
        if (fn) CdButton("Report False Negative", { onReport(false) }, height = 44.dp, fontSize = 14.sp)
    }
}

/**
 * Sends a report to Control D's team. False Positive pre-selects the matched filter or service and can also add a
 * Bypass rule for the domain(s) to the profile that answered; False Negative asks which filter should add it.
 */
@Composable
fun ReportDialog(
    falsePositive: Boolean,
    result: DomainTestResult,
    services: Map<String, Service>,
    filters: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = result.verdict
    val useServices = falsePositive && v?.source == "svc"
    // Choices: services for a service match, otherwise native filters (3rd-party lists can't be reported).
    val choices = remember(useServices, services, filters) {
        if (useServices) services.values.sortedBy { it.name.lowercase() }.map { it.pk to it.name }
        else reportExtraFilters + filters.filterKeys { !it.startsWith("x-") && reportExtraFilters.none { e -> e.first == it } }
            .toList().sortedBy { it.second.lowercase() }
    }
    var text by remember { mutableStateOf(result.domain) }
    var filter by remember { mutableStateOf(if (falsePositive) v?.match else null) }
    var comment by remember { mutableStateOf("") }
    var addBypass by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val parsed = parseReportDomains(text)
    val filterLabel = filter?.let { f -> choices.firstOrNull { it.first == f }?.second ?: specialNames[f] ?: f }
    val canSend = parsed.domains.isNotEmpty() && parsed.error == null && comment.isNotBlank() && filter != null && !sending
    val many = parsed.domains.size > 1

    CdDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (falsePositive) "Report False Positive" else "Report False Negative") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (falsePositive) "Tell Control D this shouldn't be filtered. Their team reviews it; your profile doesn't change."
                    else "Tell Control D this should be blocked. Their team reviews it; your profile doesn't change.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                )
                CdTextField(
                    text, { text = it }, label = { Text("Domains or URLs (one per line)") }, minLines = 2, maxLines = 6,
                    isError = parsed.error != null,
                    supportingText = parsed.error?.let { e -> { Text(e) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(FieldFill)
                        .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .clickable { picking = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (useServices) "Service" else "Filter", color = Palette.Muted, fontSize = 11.sp)
                        Text(filterLabel ?: "Choose which filter should block it", color = if (filter != null) Palette.Text else Palette.Muted, maxLines = 1)
                    }
                    Icon(Solar.ExpandMore, null, Modifier.size(18.dp), tint = Palette.Muted)
                }
                CdTextField(
                    comment, { comment = it.take(MAX_REPORT_COMMENT) }, label = { Text("Comment (required)") }, minLines = 2, maxLines = 5,
                    supportingText = { Text("${MAX_REPORT_COMMENT - comment.length} characters remaining") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (falsePositive && v?.profileId != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { addBypass = !addBypass }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(addBypass, { addBypass = it })
                        Text(if (many) "Also add Bypass rules for these domains to this profile" else "Also add a Bypass rule for this domain to this profile", style = MaterialTheme.typography.bodySmall)
                    }
                }
                sendError?.let { Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = canSend, onClick = {
                val f = filter ?: return@TextButton
                sending = true; sendError = null
                scope.launch {
                    try {
                        session.api.reportDomains(falsePositive, parsed.domains, f, comment.trim())
                        val ruleNote = if (falsePositive && addBypass && v?.profileId != null) {
                            runCatching { session.api.createRules(v.profileId, parsed.domains, Action(Do.BYPASS), 0, null) }
                                .fold({ " and Bypass rule added" }, { " (the Bypass rule couldn't be added: ${it.message})" })
                        } else ""
                        showToast(ctx, (if (many) "${parsed.domains.size} reports have been sent" else "Report has been sent") + ruleNote)
                        onDismiss()
                    } catch (e: Exception) {
                        sendError = e.message ?: "No reports were sent. Please try again later."
                    } finally { sending = false }
                }
            }) { if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        PickerDialog(
            if (useServices) "Service" else "Filter", choices, label = { it.second }, selected = { it.first == filter },
            onDismiss = { picking = false },
        ) { filter = it.first }
    }
}
