package com.controldmanager.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val Today get() = LocalDate.now()

/**
 * One UI style calendar: "October 2026 ▾" between month arrows, MON…SUN (Sunday in red), days of the
 * neighbouring months greyed, the chosen day in a filled green circle. Swipe or use the arrows to change
 * month; tap the title for Day / Month / Year wheels. With [end] set, the days between are banded (a range).
 */
@Composable
fun CdCalendar(
    start: LocalDate?,
    end: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    isSelectable: (LocalDate) -> Boolean = { true },
    minYear: Int = Today.year - 5,
    maxYear: Int = Today.year + 5,
    /** Whether the wheels also pick the day (single-date pickers) or only jump to a month (ranges). */
    wheelPicksDay: Boolean = true,
    /** Earliest / latest day that can be shown: months and days outside aren't listed on the wheels or reachable by arrows. */
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    val lo = YearMonth.from(minDate ?: LocalDate.of(minYear, 1, 1))
    val hi = YearMonth.from(maxDate ?: LocalDate.of(maxYear, 12, 31))
    var month by remember { mutableStateOf(YearMonth.from(start ?: Today)) }
    var wheels by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(1) }
    fun go(delta: Int) {
        val next = month.plusMonths(delta.toLong())
        if (!next.isBefore(lo) && !next.isAfter(hi)) { direction = delta; month = next }
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp)) {
                if (!wheels && month.isAfter(lo)) IconButton(onClick = { go(-1) }) { Icon(Solar.ChevronLeft, "Previous month", tint = Palette.Text) }
            }
            Row(
                Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { wheels = !wheels },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                    color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                )
                Icon(if (wheels) Solar.ArrowDropUp else Solar.ArrowDropDown, null, tint = Palette.Text)
            }
            Box(Modifier.width(48.dp)) {
                if (!wheels && month.isBefore(hi)) IconButton(onClick = { go(1) }) { Icon(Solar.ChevronRight, "Next month", tint = Palette.Text) }
            }
        }

        if (wheels) {
            DateWheels(
                date = start?.takeIf { YearMonth.from(it) == month } ?: month.atDay(1),
                showDay = wheelPicksDay,
                min = minDate ?: lo.atDay(1), max = maxDate ?: hi.atEndOfMonth(),
            ) { d ->
                month = YearMonth.from(d)
                if (wheelPicksDay && isSelectable(d)) onDayClick(d)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                DayOfWeek.entries.forEach { dow ->
                    Text(
                        dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                        Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (dow == DayOfWeek.SUNDAY) Palette.Red else Palette.Muted, fontSize = 13.sp,
                    )
                }
            }
            // Swipe sideways to change month, with the grid sliding the same way.
            var drag by remember { mutableFloatStateOf(0f) }
            val threshold = with(LocalDensity.current) { 60.dp.toPx() }
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    (slideInHorizontally(tween(260)) { it * direction / 3 } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally(tween(260)) { -it * direction / 3 } + fadeOut(tween(160)))
                },
                modifier = Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag > threshold) go(-1) else if (drag < -threshold) go(1); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, d -> drag += d }
                },
                label = "month",
            ) { m -> MonthGrid(m, start, end, isSelectable, onDayClick) }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth, start: LocalDate?, end: LocalDate?,
    isSelectable: (LocalDate) -> Boolean, onDayClick: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    Column {
        // Always six weeks, so the panel doesn't jump in height between months.
        repeat(6) { w ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dIdx ->
                    val day = gridStart.plusDays((w * 7 + dIdx).toLong())
                    val inMonth = YearMonth.from(day) == month
                    val isStart = day == start
                    val isEnd = day == end
                    val chosen = isStart || isEnd
                    val inRange = start != null && end != null && day.isAfter(start) && day.isBefore(end)
                    val enabled = isSelectable(day)
                    val sunday = day.dayOfWeek == DayOfWeek.SUNDAY
                    val bg = animateCdColor(if (chosen) Palette.Teal else Color.Transparent, tween(180), label = "day")
                    Box(
                        Modifier.weight(1f).height(46.dp)
                            .then(
                                // Band joining the two ends of a range.
                                when {
                                    inRange -> Modifier.background(Palette.Teal.copy(alpha = 0.18f))
                                    isStart && end != null && end != start -> Modifier.halfBand(right = true)
                                    isEnd && start != null && end != start -> Modifier.halfBand(right = false)
                                    else -> Modifier
                                },
                            )
                            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onDayClick(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                            Text(
                                "${day.dayOfMonth}",
                                fontSize = 18.sp,
                                fontWeight = if (chosen || day == Today) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    chosen -> Palette.OnAccent
                                    day == Today -> Palette.Teal
                                    sunday -> Palette.Red
                                    else -> Palette.Text
                                },
                                modifier = Modifier.alpha(
                                    when {
                                        chosen -> 1f
                                        !enabled -> 0.25f
                                        !inMonth -> 0.4f
                                        else -> 1f
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Day / Month / Year wheels: the centre row is the choice, in large bold white; the rows above and below fade. */
@Composable
private fun DateWheels(date: LocalDate, showDay: Boolean, min: LocalDate, max: LocalDate, onChange: (LocalDate) -> Unit) {
    val start = date.coerceIn(min, max)
    var d by remember { mutableIntStateOf(start.dayOfMonth) }
    var m by remember { mutableIntStateOf(start.monthValue) }
    var y by remember { mutableIntStateOf(start.year) }
    // Only months / days inside [min, max] are listed (e.g. no future months for Statistics).
    val mLo = if (y == min.year) min.monthValue else 1
    val mHi = if (y == max.year) max.monthValue else 12
    val mm = m.coerceIn(mLo, mHi)
    val ym = YearMonth.of(y, mm)
    val dLo = if (ym == YearMonth.from(min)) min.dayOfMonth else 1
    val dHi = if (ym == YearMonth.from(max)) max.dayOfMonth else ym.lengthOfMonth()
    val dd = d.coerceIn(dLo, dHi)
    LaunchedEffect(dd, mm, y) { onChange(LocalDate.of(y, mm, dd)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (showDay) key(y, mm) {
            Wheel((dLo..dHi).map { "%02d".format(it) }, dd - dLo, Modifier.weight(1f)) { d = dLo + it }
        }
        key(y) {
            Wheel(
                (mLo..mHi).map { java.time.Month.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase().trimEnd('.') },
                mm - mLo, Modifier.weight(1.3f),
            ) { m = mLo + it }
        }
        Wheel((min.year..max.year).map { "$it" }, y - min.year, Modifier.weight(1.3f)) { y = min.year + it }
    }
}

@Composable
private fun Wheel(items: List<String>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val rowH = 64.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, items.lastIndex))
    val centre by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0 } }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) onSelect(centre.coerceIn(0, items.lastIndex))
    }
    // Keep the day wheel inside a shorter month.
    LaunchedEffect(items.size) { if (centre > items.lastIndex) state.scrollToItem(items.lastIndex) }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = rowH),
        modifier = modifier.height(rowH * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(items.size) { i ->
            val on = i == centre
            Box(Modifier.height(rowH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    items[i], fontSize = if (on) 36.sp else 32.sp, fontWeight = FontWeight.Bold,
                    color = if (on) Palette.Text else Palette.Muted.copy(alpha = 0.45f), maxLines = 1,
                )
            }
        }
    }
}

/** Range band on one half of a cell, so it starts and ends at the centre of the end days. */
private fun Modifier.halfBand(right: Boolean) = drawBehind {
    val w = size.width / 2
    drawRect(Palette.Teal.copy(alpha = 0.18f), topLeft = androidx.compose.ui.geometry.Offset(if (right) w else 0f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
}
