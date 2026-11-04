package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controldmanager.app.api.Action
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.Do
import com.controldmanager.app.api.Proxy

/** The folder the shortcut keeps its rules in; its name is how the app recognises it (on any phone). */
internal const val YOUTUBE_PROXY_FOLDER = "YouTube Ad Blocking (DNS OTG)"

/**
 * YouTube's main and video names, as NextDNS's YouTube option routes them: the site, the app's API and every video
 * server. Thumbnails, Music, Kids and TV are left alone.
 */
internal val YouTubeProxyNames = listOf(
    "youtube.com", "www.youtube.com", "m.youtube.com",
    "youtubei.googleapis.com", "youtube.googleapis.com",
    "googlevideo.com", "*.googlevideo.com",
)

/** Turns the shortcut on through [proxy] (replacing an earlier location), or off when [proxy] is null. */
internal suspend fun setYouTubeProxy(api: ControlDApi, pid: String, proxy: Proxy?) {
    // Remove the old folder first, rules included (deleting only the folder may leave its rules behind).
    api.folders(pid).filter { it.name == YOUTUBE_PROXY_FOLDER }.forEach { old ->
        api.rules(pid, old.pk).forEach { api.deleteRule(pid, it.hostname) }
        api.deleteFolder(pid, old.pk)
    }
    if (proxy == null) return
    val action = Action(Do.REDIRECT, proxy.pk)
    api.createFolder(pid, YOUTUBE_PROXY_FOLDER, action)
    val folder = api.folders(pid).firstOrNull { it.name == YOUTUBE_PROXY_FOLDER }?.pk
        ?: throw IllegalStateException("Couldn't create the folder for YouTube")
    api.createRules(pid, YouTubeProxyNames, action, folder, "Added by DNS OTG: YouTube Ad Blocking")
}

/**
 * The 3rd-party tab's YouTube shortcut: Control D's own Redirect, through one of its proxy locations, for YouTube's
 * names (the idea behind NextDNS's YouTube option, on Control D's servers). Kept in its own Custom Rules folder, so
 * the profile's YouTube service setting is left as it was, and turning it off removes exactly what it added.
 */
@Composable
internal fun YouTubeProxyCard(pid: String) {
    val session = LocalSession.current
    val runner = rememberRunner()
    // The current location: from the folder's redirect, when the folder exists.
    val state = rememberLoader(pid) {
        val folder = session.api.folders(pid).firstOrNull { it.name == YOUTUBE_PROXY_FOLDER }
        val proxies = session.proxies()
        Pair(folder?.action?.via?.let { via -> proxies.firstOrNull { it.pk == via } } ?: folder?.let { proxies.firstOrNull() }, proxies)
    }
    var picking by remember { mutableStateOf(false) }
    val (current, proxies) = state.data ?: (null to emptyList())
    val on = current != null

    fun apply(p: Proxy?) = runner.run(if (p == null) "YouTube back to normal" else "YouTube now goes through ${p.label}") {
        setYouTubeProxy(session.api, pid, p)
        state.data = p to proxies
    }

    CdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YouTube Ad Blocking", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "BETA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Teal,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Teal.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        "Enjoy YouTube with fewer ads, with a little help from Control D's proxy locations. YouTube sees you in the " +
                            "country you pick, where it may show fewer ads; recommendations and language can change to that country.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                CdSwitch(on, { want ->
                    if (!want) apply(null)
                    else if (proxies.isEmpty()) runner.run { throw IllegalStateException("No proxy locations are available on this account") }
                    // Turning on asks where first.
                    else picking = true
                }, busy = runner.busy, enabled = state.data != null)
            }
            if (on) {
                Spacer(Modifier.height(8.dp))
                SelectBox("Through ${current!!.label}", enabled = !runner.busy) { picking = true }
            }
        }
    }

    if (picking) PickerDialog(
        "Watch YouTube through", proxies, label = { it.label }, selected = { it.pk == current?.pk },
        onDismiss = { picking = false },
    ) { apply(it) }
}
