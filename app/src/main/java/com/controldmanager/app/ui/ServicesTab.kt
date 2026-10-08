@file:OptIn(ExperimentalMaterial3Api::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.controldmanager.app.api.Action
import com.controldmanager.app.api.Do
import com.controldmanager.app.api.Proxy
import com.controldmanager.app.api.Service
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val CONFIGURED = "__configured"
private const val ALL = "__all"

@Composable
fun ServicesTab(pid: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    val categories = rememberLoader { session.categories() }
    val configured = rememberLoader(pid) { session.api.profileServices(pid) }
    val proxies = rememberLoader { runCatching { session.proxies() }.getOrDefault(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(CONFIGURED) }
    val search = rememberBottomSearch()
    val q = search.query
    BottomSearch(search, "Search services", collapsed = true)
    var editing by remember { mutableStateOf<Service?>(null) }

    val catalog = rememberLoader(selected) {
        when (selected) {
            CONFIGURED -> emptyList()
            ALL -> coroutineScope {
                session.categories().map { c -> async { session.servicesIn(c.pk) } }.awaitAll().flatten().sortedBy { it.name.lowercase() }
            }
            else -> session.servicesIn(selected).sortedBy { it.name.lowercase() }
        }
    }

    fun save(s: Service, a: Action) = runner.run("${s.name}: ${Do.label(a.doType)}${if (a.enabled) "" else " (off)"}") {
        session.api.setService(pid, s.pk, a)
        val updated = s.copy(action = a)
        val list = configured.data.orEmpty()
        configured.data = if (list.any { it.pk == s.pk }) list.map { if (it.pk == s.pk) updated else it } else list + updated
    }

    Column {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val chipsState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyRow(Modifier.weight(1f).horizontalEdgeFade({ chipsState.canScrollBackward }, { chipsState.canScrollForward }), state = chipsState, contentPadding = PaddingValues(start = 16.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                CdChip(selected == CONFIGURED, { selected = CONFIGURED }, { Text("Configured (${configured.data?.size ?: "…"})") })
            }
            item { CdChip(selected == ALL, { selected = ALL }, { Text("All") }) }
            items(categories.data.orEmpty(), key = { it.pk }) { c ->
                CdChip(selected == c.pk, { selected = c.pk }, { Text("${c.name} (${c.count})") })
            }
        }
        Spacer(Modifier.width(12.dp))
        }
        Spacer(Modifier.height(6.dp))

        val configuredMap = configured.data.orEmpty().associateBy { it.pk }
        val source = if (selected == CONFIGURED) configured else catalog
        LoaderBox(source) { list ->
            val merged = list.map { s -> configuredMap[s.pk]?.let { s.copy(action = it.action) } ?: s }
                .filter { q.isBlank() || it.name.contains(q, true) || it.pk.contains(q, true) }
                .let { l -> if (selected == CONFIGURED) l.sortedWith(compareByDescending<Service> { it.action?.enabled == true }.thenBy { it.name.lowercase() }) else l }
            // One column on phones; two (or more) side by side on landscape and tablets.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 400.dp),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, SearchPillSpace),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (merged.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Solar.Widget, if (selected == CONFIGURED) "No service rules yet. Pick a category to add one." else "No services found")
                }
                items(merged, key = { it.pk }) { s ->
                    ServiceRow(
                        s, proxies.data.orEmpty(), busy = runner.busy,
                        onClick = { editing = s },
                        onToggle = { on ->
                            val current = s.action
                            when {
                                !on && current != null -> save(s, current.copy(status = 0))
                                current != null -> save(s, current.copy(status = 1))
                                else -> save(s, Action(Do.BLOCK, status = 1))
                            }
                        },
                        onPick = { d ->
                            val current = s.action
                            val redirecting = current?.doType == Do.REDIRECT || current?.doType == Do.SPOOF
                            when {
                                // Already redirecting/spoofing and on: tapping Redirect again picks a location or spoof target.
                                d == Do.REDIRECT && redirecting && current?.enabled == true -> editing = s
                                // Off but already redirecting/spoofing: just switch it back on with the same target.
                                d == Do.REDIRECT && redirecting && current != null -> save(s, current.copy(status = 1))
                                d == Do.REDIRECT -> {
                                    val via = s.unlockLocation ?: proxies.data?.firstOrNull()?.pk
                                    if (via == null) editing = s else save(s, Action(Do.REDIRECT, via, status = 1))
                                }
                                // Any choice while off turns the rule on.
                                else -> save(s, Action(d, status = 1))
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { s ->
        RedirectSpoofDialog(
            title = s.name,
            current = s.action,
            proxies = proxies.data.orEmpty(),
            suggested = s.unlockLocation,
            note = s.warning,
            onDismiss = { editing = null },
        ) { a -> save(s, a) }
    }
}

@Composable
private fun ServiceRow(
    s: Service,
    proxies: List<Proxy>,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
) {
    val on = s.action?.enabled == true
    CdCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(s.pk, s.name, if (on) actionColor(s.action!!.doType) else Palette.Muted, size = 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    ActionPill(s.action, proxies)
                }
                Spacer(Modifier.width(8.dp))
                CdSwitch(on, onToggle, busy = busy)
            }
            Spacer(Modifier.height(10.dp))
            QuickActions(selected = s.action?.doType, active = on, busy = busy, onPick = onPick)
        }
    }
}

/**
 * Dashboard-style Block / Bypass / Redirect selector. Dimmed while the rule is off but still tappable:
 * picking an option turns the rule on. Spoof rules show on the third segment. Tapping the active
 * Redirect/Spoof segment again lets the caller open the location / spoof picker.
 */
@Composable
fun QuickActions(selected: Int?, active: Boolean, busy: Boolean, onPick: (Int) -> Unit) {
    // Highlight the tapped option right away and spin its icon until the server answers (like CdSwitch).
    var pending by remember { mutableStateOf<Int?>(null) }
    val currentBusy by rememberUpdatedState(busy)
    LaunchedEffect(selected) { if (pending == selected) pending = null }
    LaunchedEffect(busy) { if (!busy) pending = null }
    LaunchedEffect(pending) {
        // Redirect may open a location picker first; stop spinning if no request starts.
        if (pending != null) {
            kotlinx.coroutines.delay(1500)
            if (!currentBusy) pending = null
        }
    }
    val spoof = selected == Do.SPOOF
    // The third segment stands for both Redirect and Spoof.
    val shownSelected = (pending ?: selected).let { if (it == Do.SPOOF) Do.REDIRECT else it }
    val segments = listOf(
        Triple(Do.BLOCK, Solar.ForbiddenCircle, "Block"),
        Triple(Do.BYPASS, Solar.CheckCircle, "Bypass"),
        Triple(Do.REDIRECT, if (spoof) Solar.Routing else Solar.Global, if (spoof) "Spoof" else "Redirect"),
    )
    val index = segments.indexOfFirst { it.first == shownSelected }
    val lit = index >= 0 && (active || pending != null)
    val litColor = if (shownSelected == Do.REDIRECT && spoof) actionColor(Do.SPOOF) else actionColor(shownSelected ?: Do.BLOCK)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .alpha(if (active || pending != null) 1f else 0.5f)
            .clip(RoundedCornerShape(50))
            .background(Palette.Bg)
            .padding(3.dp),
    ) {
        // One pill slides between the options (same spring as the other sliders) and fades to the action's colour.
        val w = maxWidth / segments.size
        val x by androidx.compose.animation.core.animateDpAsState(
            w * index.coerceAtLeast(0),
            androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
            label = "actionPill",
        )
        val pill = animateCdColor(
            if (lit) litColor else Palette.CardHigh, androidx.compose.animation.core.tween(220), label = "actionPillColor",
        )
        if (index >= 0) Box(Modifier.offset(x = x).width(w).height(34.dp).clip(RoundedCornerShape(50)).background(pill))
        Row(Modifier.fillMaxWidth()) {
            segments.forEach { (d, icon, label) ->
                val chosen = shownSelected == d
                val spinning = pending == d
                val fg = animateCdColor(
                    when {
                        chosen && lit -> Palette.Bg
                        chosen -> Palette.Text
                        else -> Palette.Muted
                    },
                    androidx.compose.animation.core.tween(220), label = "actionFg",
                )
                // Re-tapping the active Redirect/Spoof opens its picker; re-tapping active Block/Bypass does nothing.
                val reopen = d == Do.REDIRECT && chosen && active
                val tappable = !busy && pending == null && (!(chosen && active) || reopen)
                Row(
                    Modifier
                        .width(w)
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = tappable) {
                            if (!reopen) pending = d
                            onPick(d)
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spinning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
                    else Icon(icon, null, Modifier.size(16.dp), tint = fg)
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
