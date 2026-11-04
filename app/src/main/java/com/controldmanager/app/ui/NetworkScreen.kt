package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.NetworkStatus
import com.controldmanager.app.api.PopLocation

/** One line for Preferences: whether Control D's locations are all up, opening the full list. */
@Composable
fun NetworkStatusRow(nav: NavHostController) {
    val session = LocalSession.current
    val loader = rememberLoader { session.api.network() }
    val n = loader.data
    GroupRow(
        "Control D network", first = true, last = true, fill = Palette.Card,
        subtitle = when {
            n != null && n.outages.isEmpty() -> "All ${n.locations.size} locations online"
            n != null -> "${n.outages.size} of ${n.locations.size} locations with an outage"
            loader.error != null -> "Couldn't check right now"
            else -> "Checking…"
        },
        subtitleColor = when {
            n != null && n.outages.isEmpty() -> Palette.Teal
            n != null -> Palette.Orange
            else -> Palette.Muted
        },
        icon = { Icon(Solar.Global, null, tint = Palette.Teal) },
        trailing = { Icon(Solar.ChevronRight, null, tint = Palette.Muted) },
    ) { nav.navigate("network") }
}

/** Control D's locations, as its Network page shows them: API, DNS and proxy per location, and the one serving you. */
@Composable
fun NetworkScreen(nav: NavHostController) {
    val session = LocalSession.current
    val loader = rememberLoader { session.api.network() }
    Scaffold(
        topBar = { BackTopBar(nav, "Control D network", subtitle = loader.data?.let { summary(it) }) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { n ->
            // Outages first, then the location serving this phone, then the rest by city.
            val sorted = n.locations.sortedWith(compareBy<PopLocation>({ it.healthy }, { it.code != n.currentPop }, { it.city }))
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "API, DNS and proxy at each location. N/A means the service isn't offered there (only some locations host proxies).",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    )
                }
                items(sorted, key = { it.code }) { l -> LocationRow(l, you = l.code == n.currentPop) }
            }
        }
    }
}

private fun summary(n: NetworkStatus) =
    if (n.outages.isEmpty()) "All ${n.locations.size} locations online" else "${n.outages.size} with an outage"

@Composable
private fun LocationRow(l: PopLocation, you: Boolean) {
    CdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(l.country, 18)
                Spacer(Modifier.width(10.dp))
                Text(l.city, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(l.code, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                if (you) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "You", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Palette.Teal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Teal.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceChip("API", l.api)
                ServiceChip("DNS", l.dns)
                ServiceChip("Proxy", l.proxy)
            }
        }
    }
}

@Composable
private fun ServiceChip(name: String, state: Int) {
    val (text, color) = when (state) {
        1 -> "✓ $name" to Palette.Teal
        0 -> "✕ $name down" to Palette.Red
        else -> "$name N/A" to Palette.Muted
    }
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
