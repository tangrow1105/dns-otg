package com.controldmanager.app.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.*

/**
 * Control D's dashboard notifications. Like the dashboard, read state lives on this device only: the IDs
 * already seen are saved, and on the very first load everything counts as seen (nothing shows as new).
 */
object CdNotifications {
    private const val PREFS = "controldmanager_ui"
    private const val SEEN = "seen_notifications"

    var items by mutableStateOf<List<CdNotification>?>(null)
        private set
    /** IDs that arrived since the user last opened Notifications. */
    var newKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    suspend fun load(ctx: Context, api: ControlDApi) {
        val list = api.notifications()
        items = list
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(SEEN, null)
        if (seen == null) {
            prefs.edit().putStringSet(SEEN, list.map { it.pk }.toSet()).apply()
            newKeys = emptySet()
        } else {
            newKeys = list.map { it.pk }.filter { it !in seen }.toSet()
        }
    }

    fun markAllRead(ctx: Context) {
        val list = items ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(SEEN, (prefs.getStringSet(SEEN, emptySet()).orEmpty() + list.map { it.pk }).toSet()).apply()
        newKeys = emptySet()
    }
}

/** Bell for the Preferences header, with the number of new notifications. */
@Composable
fun NotificationsButton(onClick: () -> Unit) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (CdNotifications.items == null) runCatching { CdNotifications.load(ctx, session.api) } }
    val count = CdNotifications.newKeys.size
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (count > 0) Badge(containerColor = Palette.Red) { Text("$count") } }) {
            Icon(if (count > 0) Solar.BellBing else Solar.Bell, "Notifications")
        }
    }
}

@Composable
fun NotificationsScreen(nav: NavHostController) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    // Which ones were new when the screen opened (they stay marked while it's open).
    val newAtOpen = remember { CdNotifications.newKeys }
    val loader = rememberLoader {
        CdNotifications.load(ctx, session.api)
        CdNotifications.items.orEmpty()
    }
    LaunchedEffect(loader.data) { if (loader.data != null) CdNotifications.markAllRead(ctx) }
    var open by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { BackTopBar(nav, "Notifications", "News and updates from Control D") },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { list ->
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) item { EmptyState(Solar.Bell, "No notifications") }
                items(list, key = { it.pk }) { n ->
                    NotificationCard(n, isNew = n.pk in newAtOpen, expanded = open == n.pk) { open = if (open == n.pk) null else n.pk }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: CdNotification, isNew: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val uri = LocalUriHandler.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        Modifier.fillMaxWidth().clip(CdShape).background(Palette.Card).clickable(onClick = onToggle)
            .animateContentSize().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNew) { Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Teal)); Spacer(Modifier.width(8.dp)) }
                    Text(n.title, fontWeight = FontWeight.SemiBold)
                }
                Text(relativeTime(n.date), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Solar.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.rotate(turn), tint = Palette.Muted)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(markdownText(n.message), style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            if (n.links.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScrollFaded()) {
                    n.links.forEach { l ->
                        CdButton(l.title.ifBlank { "Open" }, { runCatching { uri.openUri(l.url) } }, icon = Solar.OpenInNew, height = 40.dp, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** "8 days ago", "2 months ago". */
private fun relativeTime(unixSeconds: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        unixSeconds * 1000, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/** Small Markdown subset used by the notifications: headings, bullets, **bold**, *italic* and [links](url). */
private fun markdownText(md: String): AnnotatedString = buildAnnotatedString {
    val inline = Regex("\\*\\*(.+?)\\*\\*|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(?<![*\\w])[*_](.+?)[*_](?![*\\w])")
    md.replace("\r", "").trim().split('\n').forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw.trimEnd()
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) line = line.removeRange(heading.range)
        Regex("^\\s*[-*+]\\s+").find(line)?.let { line = "•  " + line.removeRange(it.range) }
        val start = length
        var pos = 0
        inline.findAll(line).forEach { m ->
            append(line.substring(pos, m.range.first))
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
                m.groups[2] != null -> withLink(LinkAnnotation.Url(m.groupValues[3], TextLinkStyles(SpanStyle(color = Palette.Teal, textDecoration = TextDecoration.Underline)))) { append(m.groupValues[2]) }
                else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(m.groupValues[4]) }
            }
            pos = m.range.last + 1
        }
        append(line.substring(pos))
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
