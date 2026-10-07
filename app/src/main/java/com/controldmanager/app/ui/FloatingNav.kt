@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.controldmanager.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.animate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.material3.IconButton
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** One action button shown as a round button beside the floating nav (e.g. New profile). */
class NavFab(val owner: String, val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Action buttons per screen (route). Kept per owner because during quick tab switches screens enter
 * and leave out of order; a single shared slot could be cleared by the screen that just left.
 */
class NavFabState {
    val fabs = mutableStateMapOf<String, NavFab>()
}

val LocalNavFab = staticCompositionLocalOf { NavFabState() }

/** Space a tab screen should leave at the bottom so its last item can scroll clear of the floating nav. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/** Registers this screen's main action in the round button next to the floating nav while the screen is shown. */
@Composable
fun NavFabButton(owner: String, icon: ImageVector, label: String, onClick: () -> Unit) {
    val state = LocalNavFab.current
    val current by rememberUpdatedState(onClick)
    DisposableEffect(owner, icon, label) {
        val f = NavFab(owner, icon, label) { current() }
        state.fabs[owner] = f
        onDispose { if (state.fabs[owner] === f) state.fabs.remove(owner) }
    }
}

class NavItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val PillHeight = 60.dp
private val FabSize = PillHeight
private val SideMargin = 12.dp
private val FabGap = 8.dp

/** Glass: Control D navy at 60% over a uniform 48 background blur (shared by nav, menus, sheets and toasts). */
private val Glass get() = HazeStyle(
    backgroundColor = Palette.Bg,
    tint = HazeTint(Palette.Card.copy(alpha = 0.6f)),
    blurRadius = 48.dp,
    noiseFactor = 0.06f,
)
private val GlassEdge get() = Palette.Ink.copy(alpha = 0.08f)
private val Indicator get() = Palette.Ink.copy(alpha = if (Palette.light) 0.08f else 0.16f)

/**
 * Floating pill tab bar. The pill keeps the same width and stays centred whether or not the round
 * action button is shown; the selected-tab highlight slides between tabs.
 */
@Composable
fun FloatingNavBar(items: List<NavItem>, selected: String?, fab: NavFab?, haze: HazeState, onSelect: (String) -> Unit) {
    // Switching between two tabs that both have a + (Profiles ↔ Endpoints), the new screen registers its
    // button a frame or two after the switch. Wait briefly before hiding so the button simply stays put.
    var shown by remember { mutableStateOf(fab) }
    LaunchedEffect(fab) {
        if (fab != null) shown = fab else { kotlinx.coroutines.delay(220); shown = null }
    }
    val fab = if (fab != null) fab else shown
    BoxWithConstraints(Modifier.fillMaxWidth().height(PillHeight + 8.dp), contentAlignment = Alignment.CenterStart) {
        // One width for every tab: centred when there's no action button, gliding left to make room for it.
        val pillWidth = (maxWidth - SideMargin * 2 - FabSize - FabGap).coerceAtMost(400.dp)
        // With a button, pill and button form one group, centred (on a phone that's exactly the side margins),
        // so on wide screens the button stays next to the pill instead of at the far edge.
        val groupX = (maxWidth - pillWidth - FabGap - FabSize) / 2
        val pillX by animateDpAsState(
            if (fab != null) groupX else (maxWidth - pillWidth) / 2,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "pillX",
        )
        val itemWidth = (pillWidth - 8.dp) / items.size
        val index = items.indexOfFirst { it.route == selected }.coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            itemWidth * index,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "indicator",
        )
        val pillShape = RoundedCornerShape(50)

        Box(
            Modifier
                .offset(x = pillX)
                .width(pillWidth)
                .height(PillHeight)
                .clip(pillShape)
                .hazeEffect(haze, Glass)
                .border(1.dp, GlassEdge, pillShape)
                .padding(4.dp),
        ) {
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .background(Indicator),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEach { t ->
                    val on = t.route == selected
                    val fg = animateCdColor(if (on) Palette.Text else Palette.Muted, tween(220), label = "tabFg")
                    Column(
                        Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .clip(pillShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(if (on) t.selectedIcon else t.icon, t.label, tint = fg, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(1.dp))
                        // Long labels (e.g. "Preferences") shrink a little to fit instead of being cut off.
                        androidx.compose.foundation.text.BasicText(
                            t.label,
                            modifier = Modifier.padding(horizontal = 2.dp),
                            style = androidx.compose.ui.text.TextStyle(
                                color = fg, fontSize = 11.sp, lineHeight = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            ),
                            maxLines = 1, softWrap = false,
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.25.sp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = fab != null,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioLowBouncy)) + fadeIn(),
            exit = scaleOut(tween(150)) + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterStart).offset(x = groupX + pillWidth + FabGap),
        ) {
            // Keep the last action while it animates out.
            var last by remember { mutableStateOf(fab) }
            if (fab != null) last = fab
            val f = last ?: return@AnimatedVisibility
            Box(
                Modifier
                    .size(FabSize)
                    .clip(CircleShape)
                    .hazeEffect(haze, Glass)
                    .border(1.dp, GlassEdge, CircleShape)
                    .clickable(onClick = f.onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(f.icon, f.label, tint = Palette.Teal, modifier = Modifier.size(26.dp)) }
        }
    }
}

/** Height the floating nav takes above the system navigation area. */
val FloatingNavHeight: Dp = PillHeight + 8.dp + 10.dp

/** The window's blur source, so menus can frost whatever is behind them like the nav bar does. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

private val MenuGlass get() = HazeStyle(
    backgroundColor = Palette.Bg,
    tint = HazeTint(Palette.Card.copy(alpha = 0.6f)),
    blurRadius = 48.dp,
    noiseFactor = 0.06f,
)

/** One open menu, drawn by [MenuOverlay] inside the main window so the glass can blur what's behind it. */
class HostedMenu(
    val key: Any,
    val anchor: androidx.compose.ui.geometry.Rect,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

class MenuHostState { var menu by mutableStateOf<HostedMenu?>(null) }

val LocalMenuHost = staticCompositionLocalOf<MenuHostState?> { null }

/**
 * Small pop-up menu in the One UI style: big rounded corners, roomy items, frosted glass background.
 * Drop-in for DropdownMenu; anchored to the layout it's placed in (usually the Box around its button).
 * Popups live in their own window where nothing can be blurred, so the menu is drawn by the app's
 * [MenuOverlay] instead; without a host it falls back to a regular dropdown.
 */
@Composable
fun CdMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalMenuHost.current
    if (host == null) {
        androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier, content = content)
        return
    }
    val key = remember { Any() }
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // A zero-size probe: its parent is the anchor's container, whose bounds the menu opens from.
    Spacer(Modifier.size(0.dp).onGloballyPositioned { c ->
        (c.parentLayoutCoordinates ?: c).let { p -> anchor = p.boundsInRoot() }
    })
    SideEffect {
        if (expanded) host.menu = HostedMenu(key, anchor, onDismissRequest, content)
        else if (host.menu?.key === key) host.menu = null
    }
    DisposableEffect(Unit) { onDispose { if (host.menu?.key === key) host.menu = null } }
}

private val MenuShape = CdShape

/** Draws the open [CdMenu] (if any) above everything, with a tap-to-dismiss layer and a short zoom-in. */
@Composable
fun MenuOverlay(host: MenuHostState, haze: HazeState) {
    var shown by remember { mutableStateOf<HostedMenu?>(null) }
    val open = host.menu
    if (open != null) shown = open
    val progress by androidx.compose.animation.core.animateFloatAsState(
        if (open != null) 1f else 0f,
        if (open != null) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) else tween(120),
        label = "menu",
        finishedListener = { if (it == 0f) shown = null },
    )
    val m = shown ?: return
    androidx.activity.compose.BackHandler(enabled = open != null) { m.onDismiss() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(m.key) { detectTapGestures { m.onDismiss() } },
    ) {
        androidx.compose.ui.layout.Layout(
            content = {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 200.dp, max = 320.dp)
                        .clip(MenuShape)
                        .hazeEffect(haze, MenuGlass)
                        .border(1.dp, GlassEdge, MenuShape)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(vertical = 8.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    androidx.compose.material3.MaterialTheme(
                        // Menu items use labelLarge; make it the larger body size like the reference menu.
                        typography = androidx.compose.material3.MaterialTheme.typography.let { it.copy(labelLarge = it.bodyLarge) },
                    ) { CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text) { m.content(this) } }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = constraints.maxHeight - margin * 2)
            val p = measurables.first().measure(loose)
            val a = m.anchor
            // Open under the anchor, right-aligned when it sits on the right half; flip above if there's no room.
            val alignRight = a.center.x > constraints.maxWidth / 2
            val x = (if (alignRight) a.right.toInt() - p.width else a.left.toInt())
                .coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
            val below = a.bottom.toInt()
            val above = a.top.toInt() - p.height
            val y = (if (below + p.height + margin <= constraints.maxHeight || above < margin) below else above)
                .coerceIn(margin, (constraints.maxHeight - p.height - margin).coerceAtLeast(margin))
            val originX = if (alignRight) 1f else 0f
            val originY = if (y >= below) 0f else 1f
            layout(constraints.maxWidth, constraints.maxHeight) {
                p.placeWithLayer(x, y) {
                    val s = 0.85f + 0.15f * progress
                    scaleX = s; scaleY = s
                    alpha = progress.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX, originY)
                }
            }
        }
    }
}

/** Menu row with the roomier padding of the reference menu. */
@Composable
fun CdMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) = androidx.compose.material3.DropdownMenuItem(
    text = text, onClick = onClick, modifier = modifier.heightIn(min = 52.dp),
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled,
    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
)

// ---------- Floating glass bottom sheet ----------

class HostedSheet(val key: Any, val onDismiss: () -> Unit, val content: @Composable ColumnScope.() -> Unit)

class SheetHostState { var sheet by mutableStateOf<HostedSheet?>(null) }

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

private val SheetGlass get() = HazeStyle(
    backgroundColor = Palette.Bg,
    tint = HazeTint(Palette.Card.copy(alpha = 0.6f)),
    blurRadius = 48.dp,
    noiseFactor = 0.06f,
)
private val SheetShape = RoundedCornerShape(32.dp)

/**
 * One UI style sheet: floats above the bottom edge with every corner rounded, frosted glass, a small handle,
 * drag down (or tap outside / Back) to close. Drop-in for ModalBottomSheet; drawn by [SheetOverlay] in the
 * main window so the glass can blur the screen behind it.
 */
@Composable
fun CdSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalSheetHost.current
    if (host == null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = Palette.Card, content = content)
        return
    }
    val key = remember { Any() }
    SideEffect { host.sheet = HostedSheet(key, onDismissRequest, content) }
    DisposableEffect(Unit) { onDispose { if (host.sheet?.key === key) host.sheet = null } }
}

@Composable
fun SheetOverlay(host: SheetHostState, haze: HazeState) {
    var shown by remember { mutableStateOf<HostedSheet?>(null) }
    val open = host.sheet
    if (open != null) shown = open
    val s = shown ?: return
    androidx.activity.compose.BackHandler(enabled = open != null) { s.onDismiss() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        // How far the sheet is pushed down from its resting place, in px.
        var off by remember(s.key) { mutableFloatStateOf(screenH) }
        var sheetH by remember(s.key) { mutableFloatStateOf(screenH) }
        var anim by remember(s.key) { mutableStateOf<Job?>(null) }
        fun animateTo(target: Float, then: (() -> Unit)? = null) {
            anim?.cancel()
            anim = scope.launch {
                animate(
                    off, target,
                    animationSpec = if (target == 0f) spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow) else tween(200),
                ) { v, _ -> off = v }
                then?.invoke()
            }
        }
        fun settle(velocity: Float) {
            if (off > sheetH * 0.28f || velocity > 1400f) animateTo(sheetH + 40f) { s.onDismiss() } else animateTo(0f)
        }
        LaunchedEffect(s.key, open == null) {
            if (open != null) animateTo(0f) else animateTo(sheetH + 40f) { shown = null }
        }
        val drag = remember(s.key) {
            object : NestedScrollConnection {
                // Scrolling up while the sheet is pulled down first brings the sheet back up.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && off > 0f) {
                        val used = maxOf(available.y, -off); anim?.cancel(); off += used
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }
                // Pulling down past the top of the content drags the sheet.
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        anim?.cancel(); off += available.y
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (off > 0f) { settle(available.y); return available }
                    return Velocity.Zero
                }
            }
        }
        val scrim = (0.55f * (1f - off / sheetH.coerceAtLeast(1f))).coerceIn(0f, 0.55f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .pointerInput(s.key) { detectTapGestures { s.onDismiss() } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, off.toInt()) }
                .navigationBarsPadding()
                .imePadding()
                .padding(10.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f)
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
                .onSizeChanged { sheetH = it.height + 60f }
                .clip(SheetShape)
                .hazeEffect(haze, SheetGlass)
                .border(1.dp, GlassEdge, SheetShape)
                .pointerInput(Unit) { detectTapGestures { } }
                .nestedScroll(drag)
                .draggable(
                    rememberDraggableState { d -> anim?.cancel(); off = (off + d).coerceAtLeast(0f) },
                    Orientation.Vertical,
                    onDragStopped = { v -> settle(v) },
                ),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 12.dp)
                    .size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(Palette.Muted.copy(alpha = 0.6f)),
            )
            // Same default text/icon colour the Material sheet gave its content.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text) {
                key(s.key) { s.content(this) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------- Slide selector (replaces segmented buttons) ----------

class SlideOption(val label: String, val icon: ImageVector? = null)

/**
 * Pill track with a filled pill that slides to the chosen option (icon + label), like the reference
 * Receive / Send switch. [selected] = -1 shows no choice yet.
 */
@Composable
fun SlideSelector(
    options: List<SlideOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(FieldFill)
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(
            w * selected.coerceAtLeast(0),
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "slide",
        )
        if (selected >= 0) Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(50)).background(Palette.Teal),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                val fg = animateCdColor(
                    when { !enabled -> Palette.Muted.copy(alpha = 0.5f); on -> Palette.OnAccent; else -> Palette.Text.copy(alpha = 0.8f) },
                    tween(200), label = "slideFg",
                )
                Row(
                    Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    o.icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(o.label, color = fg, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- Glass toast (replaces the snackbar look) ----------

/** Pill toast: app mark, message, and a white pill button when the toast has an action. */
@Composable
fun GlassToast(data: androidx.compose.material3.SnackbarData, haze: HazeState?) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .then(if (haze != null) Modifier.hazeEffect(haze, Glass) else Modifier.background(Palette.Card.copy(alpha = 0.93f)))
            .border(1.dp, GlassEdge, shape)
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
            .heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.app_logo), null,
            tint = Palette.Text, modifier = Modifier.size(12.dp, 13.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            data.visuals.message, color = Palette.Text, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
        )
        data.visuals.actionLabel?.let { label ->
            Box(
                Modifier.clip(shape).background(Palette.Ink).clickable { data.performAction() }.padding(horizontal = 14.dp, vertical = 5.dp),
            ) { Text(label, color = Palette.Bg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        } ?: Spacer(Modifier.width(10.dp))
    }
}

// ---------- Floating glass action bar (Cancel | Save) ----------

class GlassAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    val primary: Boolean = false,
    val destructive: Boolean = false,
    val icon: ImageVector? = null,
)

class ActionBarHostState {
    val bars = mutableStateListOf<Pair<Any, List<GlassAction>>>()
}

val LocalActionBarHost = staticCompositionLocalOf<ActionBarHostState?> { null }

/** Bottom space a screen should leave so its last field can scroll clear of the floating action bar. */
val ActionBarSpace = 96.dp

/**
 * Shows [actions] as a floating glass pill at the bottom of the screen while this screen is shown,
 * split by thin dividers, like the reference Cancel | Save bar.
 */
@Composable
fun GlassActionBar(actions: List<GlassAction>) {
    val host = LocalActionBarHost.current ?: return
    val key = remember { Any() }
    SideEffect {
        val i = host.bars.indexOfFirst { it.first === key }
        if (i >= 0) host.bars[i] = key to actions else host.bars.add(key to actions)
    }
    DisposableEffect(Unit) { onDispose { host.bars.removeAll { it.first === key } } }
}

@Composable
fun ActionBarOverlay(host: ActionBarHostState, haze: HazeState, hidden: Boolean = false) {
    // [hidden] (going back to a tab) removes the bar at once, before the nav bar appears in its place;
    // otherwise the leaving screen keeps it registered until its slide-out finishes.
    if (hidden) return
    val actions = host.bars.lastOrNull()?.second
    AnimatedVisibility(
        visible = actions != null,
        enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize(),
    ) {
        var last by remember { mutableStateOf(actions) }
        if (actions != null) last = actions
        val list = last ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .height(56.dp)
                    .clip(shape)
                    .hazeEffect(haze, Glass)
                    .border(1.dp, GlassEdge, shape)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                list.forEachIndexed { i, a ->
                    if (i > 0) Box(Modifier.width(1.dp).height(22.dp).background(Palette.Ink.copy(alpha = 0.16f)))
                    val color = when {
                        !a.enabled -> Palette.Muted.copy(alpha = 0.5f)
                        a.destructive -> Palette.Red
                        a.primary -> Palette.Teal
                        else -> Palette.Text
                    }
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .widthIn(min = 112.dp)
                            .clip(shape)
                            .clickable(enabled = a.enabled && !a.busy, onClick = a.onClick)
                            .padding(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (a.busy) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                            Spacer(Modifier.width(8.dp))
                        } else a.icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(a.label, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- Floating glass search bar (opened from a search button at the top) ----------

/** One screen's search: whether the floating bar is open, and the text. */
class BottomSearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    fun close() { open = false; query = "" }
}

/**
 * [collapsed] screens (no nav bar) always show a small "Search" pill at the bottom that widens into the
 * bar when tapped; other screens show the bar only while open, in place of the nav bar.
 */
class SearchRequest(val key: Any, val placeholder: String, val state: BottomSearchState, val collapsed: Boolean)

class SearchHostState {
    var active by mutableStateOf<SearchRequest?>(null)
    /** True while the full search bar is showing (the nav bar hides then). */
    val expanded get() = active?.state?.open == true
}

val LocalSearchHost = staticCompositionLocalOf<SearchHostState?> { null }

@Composable
fun rememberBottomSearch() = remember { BottomSearchState() }

/** Top-bar search icon; teal while a search is applied. */
@Composable
fun SearchButton(state: BottomSearchState) {
    IconButton(onClick = { state.open = true }) {
        Icon(
            androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), "Search",
            tint = if (state.query.isNotBlank()) Palette.Teal else Palette.Text,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Space a list should leave at the bottom on screens with the collapsed search pill. */
val SearchPillSpace = 84.dp

/**
 * Registers this screen's floating search. With [collapsed] (screens without a nav bar) the small pill is
 * always shown; otherwise the bar appears only while open (from a [SearchButton]).
 */
@Composable
fun BottomSearch(state: BottomSearchState, placeholder: String, collapsed: Boolean = false) {
    val host = LocalSearchHost.current ?: return
    val key = remember { Any() }
    // Read during composition so opening/closing recomposes this and updates the host.
    val show = collapsed || state.open
    SideEffect {
        val cur = host.active
        if (show) { if (cur?.key !== key || cur.placeholder != placeholder) host.active = SearchRequest(key, placeholder, state, collapsed) }
        else if (cur?.key === key) host.active = null
    }
    DisposableEffect(Unit) { onDispose { if (host.active?.key === key) host.active = null } }
}

@Composable
fun SearchOverlay(host: SearchHostState, haze: HazeState, onTab: Boolean = false) {
    // Back on a tab: a leaving screen's collapsed pill is dropped at once, before the nav bar shows,
    // rather than waiting for that screen to finish sliding out.
    if (onTab && host.active?.collapsed == true) return
    val req = host.active
    AnimatedVisibility(
        visible = req != null,
        enter = fadeIn(tween(160)) + androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 2 },
        exit = fadeOut(tween(120)) + androidx.compose.animation.slideOutVertically(tween(160)) { it / 2 },
        modifier = Modifier.fillMaxSize(),
    ) {
        var last by remember { mutableStateOf(req) }
        if (req != null) last = req
        val r = last ?: return@AnimatedVisibility
        val s = r.state
        val open = s.open || !r.collapsed
        androidx.activity.compose.BackHandler(enabled = req != null && s.open) { s.close() }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(r.key, s.open) {
            if (s.open) { kotlinx.coroutines.delay(120); runCatching { focus.requestFocus() } }
            else focusManager.clearFocus()
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // The pill widens from its collapsed size into the full bar.
            val width by animateDpAsState(
                if (open) maxWidth else 210.dp,
                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "searchWidth",
            )
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .width(width)
                    .height(50.dp)
                    .clip(shape)
                    .hazeEffect(haze, Glass)
                    .border(1.dp, GlassEdge, shape)
                    .then(if (!open) Modifier.clickable { s.open = true } else Modifier)
                    .padding(start = if (open) 20.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!open) {
                    Icon(androidx.compose.ui.res.painterResource(com.controldmanager.app.R.drawable.ic_search), null, tint = if (s.query.isNotBlank()) Palette.Teal else Palette.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.query.ifBlank { "Search" }, color = if (s.query.isNotBlank()) Palette.Text else Palette.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    androidx.compose.foundation.text.BasicTextField(
                        s.query, { s.query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Teal),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (s.query.isEmpty()) Text(r.placeholder, color = Palette.Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                inner()
                            }
                        },
                    )
                    IconButton(onClick = { s.close() }) { Icon(Solar.Close, "Close search", tint = Palette.Text) }
                }
            }
        }
    }
}

// ---------- Grouped list (several options in one rounded box, divided, under a category) ----------

/** Fill of a grouped box: Card on pages, a light frost inside glass sheets. */
val GroupFill get() = Palette.Ink.copy(alpha = 0.06f)

/** Shape of one row in a group: only the group's outer corners are rounded. */
fun groupShape(first: Boolean, last: Boolean): androidx.compose.ui.graphics.Shape {
    val r = CdCorner
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
}

/**
 * One option in a grouped list: optional icon, title, grey subtitle, trailing slot; a thin divider
 * separates it from the row above (except the first), like the reference device list.
 */
@Composable
fun GroupRow(
    title: String,
    first: Boolean,
    last: Boolean,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Palette.Text,
    subtitleColor: Color = Palette.Muted,
    selected: Boolean = false,
    fill: Color = GroupFill,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().clip(groupShape(first, last)).background(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (!first) GroupDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (subtitle != null) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { it() }; Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = if (selected) Palette.Teal else titleColor, fontSize = 16.sp, lineHeight = 22.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = subtitleColor, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
            if (selected) { Spacer(Modifier.width(8.dp)); Icon(Solar.Check, null, tint = Palette.Teal) }
        }
    }
}

/** Category label above a group. */
@Composable
fun GroupHeader(text: String, icon: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { it(); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Divider between rows of a grouped box, inset from both edges. */
@Composable
fun GroupDivider() = Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Outline.copy(alpha = 0.8f)))

// ---------- Bottom glass dialog (replaces centred pop-up dialogs) ----------

class HostedDialog(
    val key: Any,
    val onDismiss: () -> Unit,
    val title: (@Composable () -> Unit)?,
    val text: (@Composable () -> Unit)?,
    val confirm: @Composable () -> Unit,
    val dismiss: (@Composable () -> Unit)?,
    val edgeToEdge: Boolean = false,
)

class DialogHostState { val dialogs = mutableStateListOf<HostedDialog>() }

val LocalDialogHost = staticCompositionLocalOf<DialogHostState?> { null }

/**
 * One UI style dialog: a frosted panel floating at the bottom (no drag handle), title, content and the
 * actions as a "Cancel | Done" row. Drop-in for AlertDialog; dialogs opened from dialogs stack on top.
 */
@Composable
fun CdDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    /** Content uses the panel's full width (calendars need it). */
    edgeToEdge: Boolean = false,
) {
    val host = LocalDialogHost.current
    if (host == null) {
        androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text)
        return
    }
    val key = remember { Any() }
    val d = HostedDialog(key, onDismissRequest, title, text, confirmButton, dismissButton, edgeToEdge)
    SideEffect {
        val i = host.dialogs.indexOfFirst { it.key === key }
        if (i >= 0) host.dialogs[i] = d else host.dialogs.add(d)
    }
    DisposableEffect(Unit) { onDispose { host.dialogs.removeAll { it.key === key } } }
}

private val DialogShape = RoundedCornerShape(32.dp)

@Composable
fun DialogOverlay(host: DialogHostState, haze: HazeState) {
    // Dialogs stay here after they close until their exit animation has played.
    val shown = remember { mutableStateListOf<HostedDialog>() }
    SideEffect {
        host.dialogs.forEach { d ->
            val i = shown.indexOfFirst { it.key === d.key }
            if (i >= 0) shown[i] = d else shown.add(d)
        }
    }
    val top = host.dialogs.lastOrNull()?.key
    shown.toList().forEach { d ->
        key(d.key) {
            val live = host.dialogs.any { it.key === d.key }
            DialogPanel(d, haze, live = live, isTop = live && d.key === top) { shown.removeAll { it.key === d.key } }
        }
    }
}

@Composable
private fun DialogPanel(d: HostedDialog, haze: HazeState, live: Boolean, isTop: Boolean, onGone: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = isTop) { d.onDismiss() }
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(live) {
        if (live) appear.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
        else { appear.animateTo(0f, tween(200)); onGone() }
    }
    // The content is recorded each frame; once closed, that last picture slides out (its state may be gone).
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var panelH by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * appear.value))
                .then(if (live) Modifier.pointerInput(d.key) { detectTapGestures { d.onDismiss() } } else Modifier),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(10.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.88f)
                // Grow / shrink smoothly when the content changes (e.g. Standard ↔ Magic, a switch revealing options).
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
                .then(if (live) Modifier.onSizeChanged { panelH = it.height } else Modifier.height(with(density) { panelH.toDp() }))
                .graphicsLayer {
                    translationY = (1f - appear.value) * 120.dp.toPx()
                    alpha = appear.value.coerceIn(0f, 1f)
                }
                .clip(DialogShape)
                .hazeEffect(haze, Glass)
                .border(1.dp, GlassEdge, DialogShape)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
          Column(
            Modifier.fillMaxWidth().drawWithContent {
                if (live) layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
          ) {
           if (live) CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text) {
                d.title?.let {
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp)) {
                        androidx.compose.material3.ProvideTextStyle(
                            androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text),
                        ) { it() }
                    }
                }
                d.text?.let {
                    Box(Modifier.weight(1f, fill = false).padding(horizontal = if (d.edgeToEdge) 0.dp else 24.dp).padding(top = if (d.title == null) 24.dp else 12.dp)) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = Palette.Text)) { it() }
                    }
                }
                // Actions as a bottom row: Cancel | Done, white and bold like the reference (red stays red).
                androidx.compose.material3.MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(primary = Palette.Text),
                    typography = MaterialTheme.typography.copy(labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)),
                ) {
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        d.dismiss?.let {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { it() }
                            Box(Modifier.width(1.dp).height(22.dp).background(Palette.Ink.copy(alpha = 0.18f)))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { d.confirm() }
                    }
                }
            }
          }
        }
    }
}

// ---------- One UI pill button ----------

/** Fill of every regular button: a dark pill (white in light mode). */
val ButtonFill get() = Palette.mix(Color(0xFF1C2131), Color.White)

/**
 * The app's button: a dark rounded pill with an optional icon and a bold label, centred. [tint] colours the
 * icon and label (white by default, teal for a main action, red for destructive). Shows a spinner while [busy].
 */
@Composable
fun CdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    tint: Color = Palette.Text,
    height: Dp = 52.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
) {
    val fade by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.4f, label = "btnAlpha")
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(ButtonFill)
            .border(1.dp, Palette.Ink.copy(alpha = 0.05f), RoundedCornerShape(50))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = if (height < 44.dp) 14.dp else 20.dp)
            .graphicsLayer { alpha = fade },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            busy -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
            iconContent != null -> iconContent()
            icon != null -> Icon(icon, null, Modifier.size(20.dp), tint = tint)
        }
        if (busy || icon != null || iconContent != null) Spacer(Modifier.width(10.dp))
        Text(text, color = tint, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------- Top navigation (tabs inside a page) ----------

/**
 * Glass pill of icon tabs. Only the selected tab shows its label (and count) and widens to fit it,
 * so every tab fits on one line; the highlight follows the selection smoothly.
 */
@Composable
fun TopNavTabs(
    icons: List<ImageVector>,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int?> = emptyList(),
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Palette.Ink.copy(alpha = 0.05f))
            .border(1.dp, Palette.Ink.copy(alpha = 0.10f), shape)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEachIndexed { i, icon ->
            val on = i == selected
            val bg = animateCdColor(if (on) Palette.Ink.copy(alpha = 0.16f) else Color.Transparent, tween(220), label = "topTabBg")
            val fg = animateCdColor(if (on) Palette.Text else Palette.Muted, tween(220), label = "topTabFg")
            Row(
                Modifier
                    .then(if (on) Modifier else Modifier.weight(1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = if (on) 16.dp else 0.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, labels.getOrNull(i), Modifier.size(22.dp), tint = fg)
                if (on) {
                    Spacer(Modifier.width(8.dp))
                    Text(labels.getOrNull(i).orEmpty(), color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    counts.getOrNull(i)?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("$it", color = fg.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
