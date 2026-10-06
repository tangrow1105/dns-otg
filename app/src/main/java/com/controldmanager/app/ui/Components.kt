package com.controldmanager.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import com.controldmanager.app.api.Proxy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fill for fields, select boxes and unselected chips: a light tint that works on any surface and theme. */
val FieldFill get() = Palette.Ink.copy(alpha = 0.06f)

/**
 * The app's text field: a filled rounded box with no outline or underline (One UI style).
 * Same parameters as OutlinedTextField for the ones the app uses.
 */
@Composable
fun CdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: androidx.compose.ui.graphics.Shape = CdShape,
) {
    val fill = FieldFill
    TextField(
        value, onValueChange, modifier, enabled,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines, shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill, unfocusedContainerColor = fill, errorContainerColor = Palette.Red.copy(alpha = 0.08f),
            disabledContainerColor = fill.copy(alpha = 0.03f),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
            focusedLabelColor = Palette.Teal, unfocusedLabelColor = Palette.Muted,
            cursorColor = Palette.Teal, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
            focusedPlaceholderColor = Palette.Muted, unfocusedPlaceholderColor = Palette.Muted,
            focusedLeadingIconColor = Palette.Muted, unfocusedLeadingIconColor = Palette.Muted,
            focusedTrailingIconColor = Palette.Muted, unfocusedTrailingIconColor = Palette.Muted,
        ),
    )
}

/** Tappable select box (replaces outlined dropdown cards): same filled look as [CdTextField]. */
@Composable
fun CdFieldCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CdShape).background(FieldFill).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
        content = content,
    )
}

/**
 * Pill chip: tinted when selected (in [color], teal by default), soft fill otherwise.
 * Drop-in for FilterChip / AssistChip / InputChip as the app uses them.
 */
@Composable
fun CdChip(
    selected: Boolean = false,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    color: Color = Palette.Teal,
) {
    val bg = animateCdColor(if (selected) color.copy(alpha = 0.16f) else FieldFill, tween(200), label = "chipBg")
    val fg = animateCdColor(if (selected) color else Palette.Text, tween(200), label = "chipFg")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) color.copy(alpha = 0.45f) else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)) {
                leadingIcon?.let { it(); Spacer(Modifier.width(6.dp)) }
                label()
                trailingIcon?.let { Spacer(Modifier.width(4.dp)); it() }
            }
        }
    }
}

/** Animated placeholder shine for loading states. */
@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX")
    val base = Palette.Ink.copy(alpha = 0.05f)
    val hi = Palette.Ink.copy(alpha = 0.11f)
    val w = 900f
    return Brush.linearGradient(listOf(base, hi, base), start = Offset(x * 2 * w - w, 0f), end = Offset(x * 2 * w, 0f))
}

/** Loading placeholder: a few card-shaped shimmering blocks, like the list that's about to appear. */
@Composable
fun ShimmerList(modifier: Modifier = Modifier, rows: Int = 6) {
    val brush = shimmerBrush()
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows) { i ->
            Column(Modifier.fillMaxWidth().clip(CdShape).background(Palette.Card).padding(16.dp)) {
                Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.55f else 0.4f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            }
        }
    }
}

/** Empty state: icon in a soft circle, a short line of text and an optional action. */
@Composable
fun NiceEmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Teal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.Teal, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(text, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(14.dp)); it() }
    }
}

/** Proxies matching [query] by city, country or code, sorted by country then city. */
fun filterLocations(proxies: List<Proxy>, query: String): List<Proxy> =
    proxies
        .filter { query.isBlank() || it.label.contains(query, true) || it.pk.contains(query, true) || it.country.contains(query, true) }
        .sortedWith(compareBy({ it.countryName.ifBlank { it.country }.lowercase() }, { it.city.lowercase() }))

/**
 * Redirect locations grouped by country: a flag + country header, then the cities as one grouped box
 * (the Activity Log's Locations sheet). [suggested] is pinned on top; [busyPk] spins on the row being saved.
 */
@Composable
fun LocationGroups(
    proxies: List<Proxy>,
    query: String,
    selected: String?,
    suggested: String? = null,
    busyPk: String? = null,
    enabled: Boolean = true,
    onPick: (Proxy) -> Unit,
) {
    val list = remember(proxies, query) { filterLocations(proxies, query) }
    if (proxies.isEmpty()) { Text("No locations available on this account.", color = Palette.Muted, modifier = Modifier.padding(vertical = 8.dp)); return }
    if (list.isEmpty()) { Text("No matching locations", color = Palette.Muted, modifier = Modifier.padding(vertical = 8.dp)); return }
    Column {
        @Composable
        fun row(p: Proxy, title: String, first: Boolean, last: Boolean) = GroupRow(
            title, first = first, last = last, selected = p.pk == selected,
            trailing = when {
                busyPk == p.pk -> ({ CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) })
                p.pk != selected -> ({ Text(p.pk, color = Palette.Muted, style = MaterialTheme.typography.labelSmall) })
                else -> null
            },
            onClick = if (enabled) ({ onPick(p) }) else null,
        )
        list.firstOrNull { it.pk == suggested }?.let { s ->
            GroupHeader("Suggested for this service") { Icon(Solar.Stars, null, Modifier.size(18.dp), tint = Palette.Teal) }
            GroupRow(
                s.label, first = true, last = true, selected = s.pk == selected,
                icon = { FlagIcon(s.country, 24) },
                onClick = if (enabled) ({ onPick(s) }) else null,
            )
        }
        list.groupBy { it.countryName.ifBlank { it.country } }.forEach { (country, cities) ->
            GroupHeader(country) { FlagIcon(cities.first().country, 20) }
            cities.forEachIndexed { i, p -> row(p, p.city.ifBlank { p.label }, i == 0, i == cities.lastIndex) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Fades the left / right edge of a sideways-scrolling row when there's more to scroll that way,
 * so a row that runs off the screen reads as "more here" instead of being cut off.
 */
fun Modifier.horizontalEdgeFade(canLeft: () -> Boolean, canRight: () -> Boolean, width: androidx.compose.ui.unit.Dp = 28.dp) =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx()
            if (canLeft()) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w),
                size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
            if (canRight()) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
        }

@Composable
fun Modifier.horizontalScrollFaded(state: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState()) =
    horizontalEdgeFade({ state.canScrollBackward }, { state.canScrollForward }).horizontalScroll(state)
