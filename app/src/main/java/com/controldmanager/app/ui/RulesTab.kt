@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*

/** View settings from the dashboard's rules menu. */
data class RuleView(val sort: String = "name", val status: Int? = null, val action: Int? = null)

private fun List<Rule>.applyView(v: RuleView) = filter { r ->
    (v.status == null || (r.action?.status ?: 1) == v.status) && (v.action == null || r.action?.doType == v.action)
}.let { l -> if (v.sort == "updated") l.sortedWith(compareByDescending<Rule> { it.updated }.thenByDescending { it.order }) else l.sortedBy { it.hostname.lowercase() } }

private val statusChoices = listOf<Pair<Int?, String>>(null to "All statuses", 1 to "Enabled", 0 to "Disabled")
private val actionChoices = listOf<Pair<Int?, String>>(null to "All actions", Do.BLOCK to "Block", Do.BYPASS to "Bypass", Do.SPOOF to "Spoof", Do.REDIRECT to "Redirect")

/** Mirrors the dashboard's rules "⋯" menu: upload folder, sort and filter. */
@Composable
private fun RulesMenu(view: RuleView, onView: (RuleView) -> Unit, onUpload: (() -> Unit)?, onExport: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = { if (view.status != null || view.action != null) Badge(containerColor = Palette.Teal) }) {
                Icon(Solar.MoreHoriz, "Rule options")
            }
        }
        CdMenu(open, { open = false; sub = null }) {
            when (sub) {
                "status" -> statusChoices.forEach { (v, l) ->
                    CdMenuItem({ Text(l) }, { onView(view.copy(status = v)); open = false; sub = null },
                        trailingIcon = { if (view.status == v) Icon(Solar.Check, null, tint = Palette.Teal) })
                }
                "action" -> actionChoices.forEach { (v, l) ->
                    CdMenuItem({ Text(l) }, { onView(view.copy(action = v)); open = false; sub = null },
                        leadingIcon = v?.let { { Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(actionColor(it))) } },
                        trailingIcon = { if (view.action == v) Icon(Solar.Check, null, tint = Palette.Teal) })
                }
                else -> {
                    onUpload?.let { up ->
                        CdMenuItem({ Text("Upload folder") }, { open = false; up() }, leadingIcon = { Icon(Solar.FileUpload, null) })
                    }
                    onExport?.let { ex ->
                        CdMenuItem({ Text("Export folder") }, { open = false; ex() }, leadingIcon = { Icon(Solar.FileDownload, null) })
                    }
                    if (onUpload != null || onExport != null) HorizontalDivider(color = Palette.Outline)
                    MenuLabel("SORT BY")
                    listOf(Triple("name", "Alphabetical", Solar.SortByAlpha), Triple("updated", "Last updated", Solar.History)).forEach { (k, l, i) ->
                        CdMenuItem({ Text(l) }, { onView(view.copy(sort = k)); open = false }, leadingIcon = { Icon(i, null) },
                            trailingIcon = { if (view.sort == k) Icon(Solar.Check, null, tint = Palette.Teal) })
                    }
                    HorizontalDivider(color = Palette.Outline)
                    MenuLabel("FILTER BY")
                    CdMenuItem({ Text(statusChoices.first { it.first == view.status }.second) }, { sub = "status" },
                        trailingIcon = { Icon(Solar.ChevronRight, null) })
                    CdMenuItem({ Text(actionChoices.first { it.first == view.action }.second) }, { sub = "action" },
                        trailingIcon = { Icon(Solar.ChevronRight, null) })
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) = Text(
    text, style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
)

/** Reads a JSON export picked by the user; accepts raw, {"body":…} or {"config":…}. */
private fun readConfig(ctx: android.content.Context, uri: android.net.Uri): JSONObject {
    val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw IllegalStateException("Couldn't read the file")
    val json = try { JSONObject(text) } catch (e: Exception) { throw IllegalStateException("That file isn't a Control D export") }
    return json.optJSONObject("config") ?: json.optJSONObject("body") ?: json
}

/** Root folder of a profile: lists folders plus the rules that aren't in any folder. */
@Composable
fun RulesTab(nav: NavHostController, pid: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    val loader = rememberLoader(pid) { session.api.folders(pid) to session.api.rules(pid, 0) }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search domains", collapsed = true)
    var folderDialog by remember { mutableStateOf<RuleFolder?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<RuleFolder?>(null) }
    var ruleDialog by remember { mutableStateOf<Rule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(RuleView()) }
    var exportFolder by remember { mutableStateOf<RuleFolder?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uploader = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runner.run("Folder uploaded", onDone = { loader.reload() }) { session.api.importFolder(pid, readConfig(ctx, uri)) }
    }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val f = exportFolder ?: return@rememberLauncherForActivityResult
        if (uri != null) runner.run("Folder exported") {
            val json = session.api.exportFolder(pid, f.pk).toString(2)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: throw IllegalStateException("Couldn't write the file")
        }
    }

    Column {
    // Rule / Folder buttons stay put above the list; only the folders and rules scroll.
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CdButton("Rule", { newRule = true }, Modifier.weight(1f), icon = Solar.Add, tint = Palette.Teal)
        CdButton("Folder", { newFolder = true }, Modifier.weight(1f), icon = Solar.AddFolder)
        RulesMenu(view, { view = it }, onUpload = { uploader.launch(arrayOf("application/json", "text/plain", "*/*")) })
    }
    LoaderBox(loader) { (folders, rules) ->
        val shown = rules.applyView(view).filter { q.isBlank() || it.hostname.contains(q, true) || it.comment?.contains(q, true) == true }
        val sortedFolders = if (view.sort == "name") folders.sortedBy { it.name.lowercase() } else folders.sortedByDescending { it.pk }
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, SearchPillSpace), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (folders.isNotEmpty()) {
                item { SectionHeader("Folders (${folders.size})") }
                items(sortedFolders, key = { "f${it.pk}" }) { f ->
                    FolderCard(
                        f, proxies.data.orEmpty(),
                        onOpen = { nav.navigate("folder/$pid/${f.pk}") },
                        onEdit = { folderDialog = f },
                        onDelete = { deleteFolder = f },
                        onExport = { exportFolder = f; exporter.launch("${f.name.replace(Regex("[^A-Za-z0-9 _-]"), "")}.json") },
                    )
                }
            }
            item {
                val filtered = view.status != null || view.action != null
                SectionHeader("Rules (${if (filtered) "${shown.size} of " else ""}${rules.size})")
            }
            rulesList(shown, proxies.data.orEmpty(), runner, pid, session.api, onEdit = { ruleDialog = it }) { loader.reload() }
            if (rules.isEmpty()) item { EmptyState(Solar.Rules, "No custom rules in the root folder") }
        }

        if (newRule || ruleDialog != null) {
            RuleEditorDialog(
                existing = ruleDialog, folders = folders, defaultFolder = 0, proxies = proxies.data.orEmpty(),
                onDismiss = { newRule = false; ruleDialog = null },
            ) { hosts, action, folder, comment ->
                val existing = ruleDialog
                runner.run(if (existing == null) "Rule added" else "Rule updated", onDone = { loader.reload() }) {
                    if (existing == null) session.api.createRules(pid, hosts, action, folder, comment)
                    else session.api.modifyRules(pid, hosts, action, folder, comment)
                }
            }
        }
    }
    }

    if (newFolder || folderDialog != null) {
        val f = folderDialog
        FolderEditorDialog(
            f, proxies.data.orEmpty(), loader.data?.first.orEmpty(), busy = runner.busy,
            onDismiss = { newFolder = false; folderDialog = null },
        ) { name, action ->
            // Stays open with a spinner on Create/Save until the folder is saved.
            runner.run(if (f == null) "Folder created" else "Folder updated", onDone = { newFolder = false; folderDialog = null; loader.reload() }) {
                if (f == null) session.api.createFolder(pid, name, action) else session.api.modifyFolder(pid, f.pk, name, action)
            }
        }
    }
    deleteFolder?.let { f ->
        ConfirmDialog(
            "Delete folder “${f.name}”?", "The folder and all ${f.count} rules inside it will be deleted.",
            onDismiss = { deleteFolder = null },
        ) { runner.run("Folder deleted", onDone = { loader.reload() }) { session.api.deleteFolder(pid, f.pk) } }
    }
}

/** Rules inside a single folder. */
@Composable
fun FolderScreen(nav: NavHostController, pid: String, fid: Int) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    val loader = rememberLoader(pid, fid) { session.api.folders(pid) to session.api.rules(pid, fid) }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search domains", collapsed = true)
    var ruleDialog by remember { mutableStateOf<Rule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(RuleView()) }
    val folder = loader.data?.first?.firstOrNull { it.pk == fid }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runner.run("Folder exported") {
            val json = session.api.exportFolder(pid, fid).toString(2)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: throw IllegalStateException("Couldn't write the file")
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(nav, folder?.name ?: "Folder", subtitle = folder?.action?.let { "Folder action: ${Do.label(it.doType)}" }) {
                RulesMenu(view, { view = it }, onUpload = null, onExport = {
                    exporter.launch("${(folder?.name ?: "folder").replace(Regex("[^A-Za-z0-9 _-]"), "")}.json")
                })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newRule = true }, containerColor = Palette.Teal) { Icon(Solar.Add, "Add rule") }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { (folders, rules) ->
            val shown = rules.applyView(view).filter { q.isBlank() || it.hostname.contains(q, true) || it.comment?.contains(q, true) == true }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rulesList(shown, proxies.data.orEmpty(), runner, pid, session.api, onEdit = { ruleDialog = it }) { loader.reload() }
                if (rules.isEmpty()) item { EmptyState(Solar.Rules, "This folder is empty") }
            }
            if (newRule || ruleDialog != null) {
                RuleEditorDialog(
                    existing = ruleDialog, folders = folders, defaultFolder = fid, proxies = proxies.data.orEmpty(),
                    onDismiss = { newRule = false; ruleDialog = null },
                ) { hosts, action, f, comment ->
                    val existing = ruleDialog
                    runner.run(if (existing == null) "Rule added" else "Rule updated", onDone = { loader.reload() }) {
                        if (existing == null) session.api.createRules(pid, hosts, action, f, comment)
                        else session.api.modifyRules(pid, hosts, action, f, comment)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.rulesList(
    rules: List<Rule>,
    proxies: List<Proxy>,
    runner: Runner,
    pid: String,
    api: ControlDApi,
    onEdit: (Rule) -> Unit,
    reload: () -> Unit,
) {
    items(rules, key = { "r_" + it.hostname }) { r ->
        var confirmDelete by remember { mutableStateOf(false) }
        RuleRow(
            r, proxies, busy = runner.busy,
            onClick = { onEdit(r) },
            onToggle = { on ->
                val a = r.action ?: return@RuleRow
                runner.run(onDone = reload) { api.modifyRules(pid, listOf(r.hostname), a.copy(status = if (on) 1 else 0), r.group, null) }
            },
            onDelete = { confirmDelete = true },
        )
        if (confirmDelete) {
            ConfirmDialog("Delete rule?", r.hostname, onDismiss = { confirmDelete = false }) {
                runner.run("Rule deleted", onDone = reload) { api.deleteRule(pid, r.hostname) }
            }
        }
    }
}

@Composable
private fun RuleRow(r: Rule, proxies: List<Proxy>, busy: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    CdCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.hostname, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                ActionPill(r.action, proxies)
                r.action?.ttl?.let { t ->
                    val expired = t * 1000 <= System.currentTimeMillis()
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Solar.Stopwatch, null, Modifier.size(14.dp), tint = if (expired) Palette.Red else Palette.Orange)
                        Spacer(Modifier.width(4.dp))
                        Text(expiryText(t), style = MaterialTheme.typography.labelSmall, color = if (expired) Palette.Red else Palette.Orange)
                    }
                }
                r.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (r.action != null) CdSwitch(r.action.enabled, onToggle, busy = busy)
            IconButton(onClick = onDelete) { Icon(Solar.Delete, "Delete", tint = Palette.Muted) }
        }
    }
}

@Composable
private fun FolderCard(f: RuleFolder, proxies: List<Proxy>, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    CdCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (f.name) { MAGIC_BYPASS -> Solar.TransferVertical; MAGIC_NO_LOG -> Solar.EyeClosed; else -> Solar.Folder },
                null, tint = if (isMagicFolder(f.name)) Palette.Teal else f.action?.let { actionColor(it.doType) } ?: Palette.Muted,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.count} rules", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    if (f.action != null) { Spacer(Modifier.width(8.dp)); ActionPill(f.action, proxies) }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Solar.MoreVert, "Folder options") }
                CdMenu(menu, { menu = false }) {
                    CdMenuItem({ Text("Edit") }, { menu = false; onEdit() }, leadingIcon = { Icon(Solar.Edit, null) })
                    CdMenuItem({ Text("Export…") }, { menu = false; onExport() }, leadingIcon = { Icon(Solar.FileDownload, null) })
                    CdMenuItem({ Text("Delete", color = Palette.Red) }, { menu = false; onDelete() }, leadingIcon = { Icon(Solar.Delete, null, tint = Palette.Red) })
                }
            }
        }
    }
}

/**
 * Create / Edit Rule, laid out like the dashboard's dialog: domain chips, the action name and description
 * beside the Block / Bypass / Redirect selector, a timer for the rule's expiry, folder (create only) and comment.
 */
@Composable
private fun RuleEditorDialog(
    existing: Rule?,
    folders: List<RuleFolder>,
    defaultFolder: Int,
    proxies: List<Proxy>,
    onDismiss: () -> Unit,
    initialHosts: String = "",
    title: String? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    onSave: (List<String>, Action, Int, String?) -> Unit,
) {
    val initial = existing?.action
    var chips by remember { mutableStateOf(existing?.let { listOf(it.hostname) } ?: splitHosts(initialHosts)) }
    var input by remember { mutableStateOf("") }
    var doType by remember { mutableIntStateOf(initial?.doType?.takeIf { it >= 0 } ?: Do.BLOCK) }
    var redirectVia by remember { mutableStateOf(initial?.via?.takeIf { initial.doType == Do.REDIRECT } ?: proxies.firstOrNull()?.pk.orEmpty()) }
    var spoofVia by remember { mutableStateOf(initial?.via?.takeIf { initial.doType == Do.SPOOF }.orEmpty()) }
    var spoofV6 by remember { mutableStateOf(initial?.viaV6.orEmpty()) }
    var ttl by remember { mutableStateOf(initial?.ttl) }
    var comment by remember { mutableStateOf(existing?.comment.orEmpty()) }
    var folder by remember { mutableIntStateOf(existing?.group ?: defaultFolder) }
    var pickFolder by remember { mutableStateOf(false) }
    var pickLocation by remember { mutableStateOf(false) }
    var pickExpiry by remember { mutableStateOf(false) }
    val hosts = (chips + splitHosts(input)).distinct()
    val valid = hosts.isNotEmpty() && when (doType) {
        Do.SPOOF -> spoofVia.isNotBlank()
        Do.REDIRECT -> redirectVia.isNotBlank()
        else -> true
    }

    CdDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (existing == null) "Create Rule" else "Edit Rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                header?.invoke(this)
                Text("Domains, Hostnames & Geo Rules", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().clip(CdShape).background(Palette.Bg)
                        .border(1.dp, Palette.Outline, CdShape).padding(8.dp),
                ) {
                    if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { h ->
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.CardHigh).padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(h, style = MaterialTheme.typography.bodySmall)
                                if (existing == null) Icon(
                                    Solar.Close, "Remove $h",
                                    Modifier.padding(start = 4.dp).size(16.dp).clip(CircleShape).clickable { chips = chips - h },
                                    tint = Palette.Muted,
                                )
                            }
                        }
                    }
                    if (existing == null) {
                        // Typing a space, comma or Enter turns the text into a chip, like the dashboard.
                        androidx.compose.foundation.text.BasicTextField(
                            input,
                            { v ->
                                if (v.any { it == ' ' || it == ',' || it == '\n' }) {
                                    chips = (chips + splitHosts(v)).distinct(); input = ""
                                } else input = v
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Teal),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                chips = (chips + splitHosts(input)).distinct(); input = ""
                            }),
                            modifier = Modifier.fillMaxWidth().padding(top = if (chips.isEmpty()) 4.dp else 8.dp, bottom = 4.dp, start = 2.dp),
                            decorationBox = { inner ->
                                if (input.isEmpty() && chips.isEmpty()) Text("Enter domains/hostnames", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                                inner()
                            },
                        )
                    }
                }
                if (existing == null) Text("Use *. for wildcards, e.g. *.example.com", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Do.label(doType), color = actionColor(doType), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (doType) {
                                Do.BLOCK -> "Prevents domain from resolving"
                                Do.BYPASS -> "Forces domain to be resolved to original IP address"
                                Do.SPOOF -> "Resolves domain to an IP or hostname of your choice"
                                else -> "Routes traffic through a Control D proxy location"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Expiry timer, as the dashboard's stopwatch next to the selector.
                    IconButton(onClick = { pickExpiry = true }) {
                        Icon(Solar.Stopwatch, "Set the rule expiration date", tint = if ((ttl ?: 0) > 0) Palette.Teal else Palette.Muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                RuleActionSelector(doType) { d -> if (!(d == Do.REDIRECT && doType == Do.SPOOF)) doType = d }
                RedirectOrSpoof(doType) { doType = it }
                when (doType) {
                    Do.REDIRECT -> {
                        Spacer(Modifier.height(8.dp))
                        val p = proxies.firstOrNull { it.pk == redirectVia }
                        CdFieldCard(onClick = { pickLocation = true }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                FlagIcon(p?.country, 24)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Location", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                                    Text(p?.label ?: redirectVia.ifBlank { "Choose…" })
                                }
                                Icon(Solar.ExpandMore, null)
                            }
                        }
                    }
                    Do.SPOOF -> {
                        Spacer(Modifier.height(8.dp))
                        CdTextField(spoofVia, { spoofVia = it }, label = { Text("IPv4 or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        CdTextField(spoofV6, { spoofV6 = it }, label = { Text("IPv6 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                ttl?.takeIf { it > 0 }?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Teal.copy(alpha = 0.12f)).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Solar.Stopwatch, null, Modifier.size(18.dp), tint = Palette.Teal)
                        Spacer(Modifier.width(8.dp))
                        Text(expiryText(t), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).clickable { pickExpiry = true })
                        // An existing expiry is removed by sending -1, as the dashboard does.
                        IconButton(onClick = { ttl = if (initial?.ttl != null) -1 else null }) { Icon(Solar.Close, "Remove expiry", Modifier.size(18.dp)) }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Outline)
                if (existing == null) {
                    Text("Choose Folder", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Spacer(Modifier.height(6.dp))
                    CdFieldCard(onClick = { pickFolder = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Solar.Folder, null, tint = Palette.Muted)
                            Spacer(Modifier.width(10.dp))
                            Text(folders.firstOrNull { it.pk == folder }?.name ?: "Root Folder", Modifier.weight(1f))
                            Icon(Solar.ExpandMore, null)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row {
                    Text("Comment", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
                    Text("${64 - comment.length} characters remaining", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
                Spacer(Modifier.height(6.dp))
                CdTextField(comment, { if (it.length <= 64) comment = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val via = when (doType) { Do.SPOOF -> spoofVia.trim(); Do.REDIRECT -> redirectVia; else -> null }
                val v6 = spoofV6.trim().takeIf { doType == Do.SPOOF && it.isNotBlank() }
                val action = Action(doType, via, v6, initial?.status ?: 1, ttl)
                // Empty comment on edit clears it; on create we only send when given.
                val c = if (existing != null) comment.trim() else comment.trim().ifBlank { null }
                onSave(if (existing != null) listOf(existing.hostname) else hosts, action, folder, c)
                onDismiss()
            }) { Text(if (existing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickFolder) {
        PickerDialog(
            "Folder", listOf<RuleFolder?>(null) + folders,
            label = { it?.name ?: "Root Folder" },
            selected = { (it?.pk ?: 0) == folder },
            onDismiss = { pickFolder = false },
        ) { folder = it?.pk ?: 0 }
    }
    if (pickLocation) LocationPicker(proxies, redirectVia, onDismiss = { pickLocation = false }) { redirectVia = it }
    if (pickExpiry) ExpiryDialog((ttl ?: 0).takeIf { it > 0 }, onDismiss = { pickExpiry = false }) { ttl = it }
}

private fun splitHosts(s: String) = s.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

private val expiryFmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())

/** "Expires 12 Oct 2026, 14:00 · in 7 days", or "Expired …" once past. */
fun expiryText(ttl: Long): String {
    val left = ttl * 1000 - System.currentTimeMillis()
    val at = expiryFmt.format(java.util.Date(ttl * 1000))
    return if (left <= 0) "Expired $at" else "Expires $at · in ${durationText(left)}"
}

private fun durationText(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 60 -> "${m.coerceAtLeast(1)} min"
        m < 60 * 24 -> "${m / 60} h" + (m % 60).let { if (it > 0) " $it min" else "" }
        m < 60 * 24 * 60 -> "${m / (60 * 24)} days"
        else -> "${m / (60 * 24 * 30)} months"
    }
}

/** Block / Bypass / Redirect, same look as the services selector; the third segment reads Spoof for spoof rules. */
@Composable
private fun RuleActionSelector(selected: Int, onPick: (Int) -> Unit) {
    // Same sliding selector as Services (always "on" inside the editor).
    QuickActions(selected = selected, active = true, busy = false, onPick = onPick)
}

/** Under the Redirect segment: the same Redirect | Spoof switch as the service sheet, so there's one place to pick it. */
@Composable
private fun RedirectOrSpoof(doType: Int, onChange: (Int) -> Unit) {
    androidx.compose.animation.AnimatedVisibility(doType == Do.REDIRECT || doType == Do.SPOOF) {
        Column {
            Spacer(Modifier.height(10.dp))
            SlideSelector(
                listOf(SlideOption("Redirect", Solar.Global), SlideOption("Spoof", Solar.Routing)),
                if (doType == Do.SPOOF) 1 else 0, { onChange(if (it == 1) Do.SPOOF else Do.REDIRECT) },
            )
        }
    }
}

/** Rule expiry: quick presets, or a date then a time (the dashboard allows any future date). */
@Composable
private fun ExpiryDialog(current: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val now = remember { System.currentTimeMillis() }
    var date by remember {
        mutableStateOf(current?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() })
    }
    val initCal = remember { java.util.Calendar.getInstance().apply { timeInMillis = current?.let { it * 1000 } ?: (now + 3_600_000) } }
    val timeState = rememberTimePickerState(initCal.get(java.util.Calendar.HOUR_OF_DAY), initCal.get(java.util.Calendar.MINUTE))
    when (step) {
        0 -> CdDialog(
            onDismissRequest = onDismiss,
            title = { Text("Rule expiration") },
            text = {
                Column {
                    Text("The rule stops applying at this time.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.height(8.dp))
                    listOf("1 hour" to 3_600L, "1 day" to 86_400L, "1 week" to 604_800L, "1 month" to 2_592_000L).forEach { (label, secs) ->
                        Text(
                            label,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(now / 1000 + secs); onDismiss() }.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { step = 1 }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Solar.CalendarMonth, null, Modifier.size(18.dp), tint = Palette.Teal)
                        Spacer(Modifier.width(8.dp))
                        Text("Pick date & time…", color = Palette.Teal)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        1 -> CdDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(enabled = date != null, onClick = { step = 2 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            edgeToEdge = true,
            text = {
                CdCalendar(
                    start = date, end = null, onDayClick = { date = it },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    isSelectable = { !it.isBefore(java.time.LocalDate.now()) },
                    minYear = java.time.LocalDate.now().year, maxYear = java.time.LocalDate.now().year + 10, minDate = java.time.LocalDate.now(),
                )
            },
        )
        else -> CdDialog(
            onDismissRequest = onDismiss,
            title = { Text("Expiration time") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val day = date ?: java.time.LocalDate.now()
                    val at = day.atTime(timeState.hour, timeState.minute).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                    onPick(maxOf(at, System.currentTimeMillis() / 1000 + 60)); onDismiss()
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { step = 1 }) { Text("Back") } },
        )
    }
}

/** Magic folders are folders with these exact names (docs.controld.com/docs/magic-folders). */
const val MAGIC_BYPASS = "Control D Bypass"
const val MAGIC_NO_LOG = "Do Not Log"
fun isMagicFolder(name: String) = name == MAGIC_BYPASS || name == MAGIC_NO_LOG

/**
 * Create / Edit Folder, as the dashboard: Standard (any name, optional Folder Rule) or Magic
 * (Control D Bypass / Do Not Log, which are named folders; Bypass takes no folder rule).
 */
@Composable
private fun FolderEditorDialog(
    existing: RuleFolder?, proxies: List<Proxy>, folders: List<RuleFolder>, busy: Boolean,
    onDismiss: () -> Unit, onSave: (String, Action) -> Unit,
) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var magic by remember { mutableStateOf(existing?.name?.let(::isMagicFolder) == true) }
    var magicType by remember { mutableStateOf(existing?.name?.takeIf(::isMagicFolder)) }
    var name by remember { mutableStateOf(existing?.name?.takeUnless(::isMagicFolder).orEmpty()) }
    val initial = existing?.action?.takeIf { it.doType >= 0 }
    var ruleOn by remember { mutableStateOf(initial != null) }
    var doType by remember { mutableIntStateOf(initial?.doType ?: Do.BLOCK) }
    var redirectVia by remember { mutableStateOf(initial?.via?.takeIf { initial.doType == Do.REDIRECT } ?: proxies.firstOrNull()?.pk.orEmpty()) }
    var spoofVia by remember { mutableStateOf(initial?.via?.takeIf { initial.doType == Do.SPOOF }.orEmpty()) }
    var pickLocation by remember { mutableStateOf(false) }
    // A magic folder can exist once per profile.
    val taken = folders.filter { it.pk != existing?.pk }.map { it.name }.toSet()
    val bypassMagic = magic && magicType == MAGIC_BYPASS
    val finalName = if (magic) magicType.orEmpty() else name.trim()
    val ruleValid = !ruleOn || bypassMagic || when (doType) {
        Do.SPOOF -> spoofVia.isNotBlank()
        Do.REDIRECT -> redirectVia.isNotBlank()
        else -> true
    }
    val valid = finalName.isNotBlank() && finalName !in taken && ruleValid

    CdDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Create Folder" else "Edit Folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolderKindCard("Standard", "Organize or enforce a single rule for all DNS queries", Solar.Folder, !magic, Modifier.weight(1f)) { magic = false }
                    FolderKindCard("Magic", "Change how matching DNS queries are handled", Solar.MagicStick, magic, Modifier.weight(1f), badge = "NEW") {
                        magic = true
                        // Start on Control D Bypass (like the dashboard), or Do Not Log if Bypass already exists.
                        if (magicType == null || magicType in taken) {
                            magicType = listOf(MAGIC_BYPASS, MAGIC_NO_LOG).firstOrNull { it !in taken }
                            if (magicType == MAGIC_BYPASS) ruleOn = false
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (!magic) {
                    Text("Folder Name", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Spacer(Modifier.height(6.dp))
                    CdTextField(
                        name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter folder name") },
                        isError = name.trim() in taken,
                        supportingText = if (name.trim() in taken) ({ Text("A folder with this name already exists") }) else null,
                    )
                } else {
                    Text("Choose Type of Magic", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        Triple(MAGIC_BYPASS, "Skip Control D for these domains.", "Queries resolve through your network's default DNS. Best for Active Directory, internal services and captive portals."),
                        Triple(MAGIC_NO_LOG, "Resolve normally, keep out of Analytics.", "Rules still apply, but matching queries never appear in your Activity Log or Statistics. Silence noisy domains or hide sensitive lookups."),
                    ).forEach { (t, headline, body) ->
                        val exists = t in taken
                        MagicCard(
                            t, headline, if (exists) "Already in this profile." else body,
                            if (t == MAGIC_BYPASS) Solar.TransferVertical else Solar.EyeClosed,
                            selected = magicType == t, enabled = !exists,
                        ) { magicType = t; if (t == MAGIC_BYPASS) ruleOn = false }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Folder Rule: one action applied to every domain in the folder.
                Column(
                    Modifier.fillMaxWidth().clip(CdShape).background(GroupFill).alpha(if (bypassMagic) 0.45f else 1f).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Folder Rule", fontWeight = FontWeight.SemiBold)
                                if (bypassMagic) { Spacer(Modifier.width(8.dp)); Pill("N/A", Palette.Muted) }
                            }
                            Text(
                                if (bypassMagic) "Not applicable. Bypass folders skip Control D entirely." else "Enforces a single rule on all domains in this folder.",
                                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                            )
                        }
                        CdSwitch(ruleOn && !bypassMagic, { ruleOn = it }, enabled = !bypassMagic)
                    }
                    if (ruleOn && !bypassMagic) {
                        Spacer(Modifier.height(10.dp))
                        RuleActionSelector(doType) { d -> if (!(d == Do.REDIRECT && doType == Do.SPOOF)) doType = d }
                        RedirectOrSpoof(doType) { doType = it }
                        when (doType) {
                            Do.REDIRECT -> {
                                Spacer(Modifier.height(8.dp))
                                val p = proxies.firstOrNull { it.pk == redirectVia }
                                CdFieldCard(onClick = { pickLocation = true }, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FlagIcon(p?.country, 24); Spacer(Modifier.width(10.dp))
                                        Text(p?.label ?: redirectVia.ifBlank { "Choose location" }, Modifier.weight(1f))
                                        Icon(Solar.ExpandMore, null)
                                    }
                                }
                            }
                            Do.SPOOF -> {
                                Spacer(Modifier.height(8.dp))
                                CdTextField(spoofVia, { spoofVia = it }, label = { Text("IPv4 or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (magic) {
                    TextButton(onClick = { uri.openUri("https://docs.controld.com/docs/magic-folders") }) {
                        Icon(Solar.Info, null, Modifier.size(16.dp), tint = Palette.Muted)
                        Spacer(Modifier.width(6.dp))
                        Text("Magic Folders Guide", color = Palette.Muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                val action = if (!ruleOn || bypassMagic) Action(-1, status = 1)
                else Action(doType, when (doType) { Do.SPOOF -> spoofVia.trim(); Do.REDIRECT -> redirectVia; else -> null }, status = 1)
                onSave(finalName, action)
            }) { BusyLabel(if (existing == null) "Create" else "Save", busy) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickLocation) LocationPicker(proxies, redirectVia, onDismiss = { pickLocation = false }) { redirectVia = it }
}

@Composable
private fun FolderKindCard(title: String, body: String, icon: ImageVector, selected: Boolean, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    val edge = animateCdColor(if (selected) Palette.Teal.copy(alpha = 0.8f) else Palette.Outline.copy(alpha = 0.6f), label = "kindEdge")
    Column(
        modifier.clip(CdShape).background(if (selected) Palette.CardHigh else GroupFill)
            .border(1.dp, edge, CdShape).clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) Palette.Text else Palette.Muted)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, color = if (selected) Palette.Text else Palette.Muted)
            badge?.let { Spacer(Modifier.width(6.dp)); Pill(it, Palette.Teal) }
        }
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun MagicCard(title: String, headline: String, body: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val edge = animateCdColor(if (selected) Palette.Teal.copy(alpha = 0.8f) else Palette.Outline.copy(alpha = 0.6f), label = "magicEdge")
    Row(
        Modifier.fillMaxWidth().clip(CdShape).background(if (selected) Palette.CardHigh else GroupFill)
            .border(1.dp, edge, CdShape).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Teal.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Palette.Teal)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(8.dp))
        if (selected) Icon(Solar.CheckCircle, null, tint = Palette.Teal)
        else Box(Modifier.size(22.dp).border(1.5.dp, Palette.Muted, CircleShape))
    }
}

/**
 * Create Rule from the Activity Log, as the dashboard's dialog: the selected domains, an action,
 * and the profile + folder to put the rule in.
 */
@Composable
fun CreateRuleFromLogDialog(
    domains: List<String>, initialProfileId: String?,
    /** Owned by the calling screen: the save must outlive this dialog, which closes as soon as it starts. */
    runner: Runner,
    onDismiss: () -> Unit, onCreated: () -> Unit = {},
) {
    val session = LocalSession.current
    val profiles = rememberLoader { session.api.profiles() }
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    var pid by remember { mutableStateOf(initialProfileId) }
    val list = profiles.data.orEmpty()
    val chosen = list.firstOrNull { it.pk == pid } ?: list.firstOrNull()
    val folders = rememberLoader(chosen?.pk) { chosen?.let { runCatching { session.api.folders(it.pk) }.getOrDefault(emptyList()) } ?: emptyList() }
    var pickProfile by remember { mutableStateOf(false) }
    if (chosen == null) {
        if (profiles.error != null) CdDialog(onDismiss, confirmButton = { TextButton(onDismiss) { Text("Close") } }, text = { Text(profiles.error!!) })
        return
    }
    // Recreate the editor per profile so the folder choice resets to that profile's root.
    key(chosen.pk) {
        RuleEditorDialog(
            existing = null, folders = folders.data.orEmpty(), defaultFolder = 0, proxies = proxies.data.orEmpty(),
            onDismiss = onDismiss, initialHosts = domains.joinToString("\n"), title = "Create Rule",
            header = {
                CdFieldCard(onClick = { pickProfile = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Solar.Tuning, null, tint = Palette.Muted)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Profile", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                            Text(chosen.name)
                        }
                        Icon(Solar.ExpandMore, null)
                    }
                }
                Spacer(Modifier.height(8.dp))
            },
        ) { hosts, action, folder, comment ->
            runner.run("Rule added to ${chosen.name}", onDone = onCreated) { session.api.createRules(chosen.pk, hosts, action, folder, comment) }
            onDismiss()
        }
    }
    if (pickProfile) {
        PickerDialog("Profile", list, label = { it.name }, selected = { it.pk == chosen.pk }, onDismiss = { pickProfile = false }) { pid = it.pk }
    }
}
