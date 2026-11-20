package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.StatScope
import com.controldmanager.app.api.TimeRange
import com.controldmanager.app.api.TrafficTaxonomy
import com.controldmanager.app.api.TrafficTaxonomy.Companion.GROUP_PREFIX
import com.controldmanager.app.api.TrafficTaxonomy.Companion.UNCLASSIFIED
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

/**
 * What Traffic Categories shows. [total], [counts] and [groupCounts] ignore the category refinement (the top
 * level and the picker); [refinedTotal] and [refinedCounts] are for the refined group or category.
 */
internal class CategoryStats(
    val tax: TrafficTaxonomy,
    val total: Long,
    val counts: Map<String, Long>,
    val groupCounts: Map<String, Long>,
    val refinedTotal: Long?,
    val refinedCounts: Map<String, Long>?,
)

/**
 * The dashboard's calls: per-category counts, then one count per group with its categories as cid[], which
 * counts a query once however many of the group's categories it has. Only categories that had queries are sent.
 */
internal suspend fun loadCategoryStats(api: ControlDApi, region: String, range: TimeRange, scope: StatScope): CategoryStats = coroutineScope {
    val tax = async { api.trafficTaxonomy() }
    val base = scope.copy(categories = null)
    val total = async { api.statCount(region, range, base) }
    val counts = async { api.statCategories(region, range, base) }
    val refinedTotal = scope.categories?.let { async { api.statCount(region, range, scope) } }
    val refinedCounts = scope.categories?.let { async { api.statCategories(region, range, scope) } }
    val present = counts.await()
    val groupCounts = tax.await().groups.map { g ->
        async {
            val ids = g.categoryIds.filter { present.containsKey(it) }
            g.id to if (ids.isEmpty()) 0L else api.statCount(region, range, base.copy(categories = ids))
        }
    }.awaitAll().toMap()
    CategoryStats(tax.await(), total.await(), present, groupCounts, refinedTotal?.await(), refinedCounts?.await())
}

/** The chip above Statistics while a category refinement is on. */
internal fun categoryChipLabel(tax: TrafficTaxonomy?, refinement: String): String {
    val name = tax?.label(refinement) ?: "…"
    return if (refinement.startsWith(GROUP_PREFIX)) "Category Group: $name" else "Category: $name"
}

/**
 * Statistics' Traffic Categories, as on the dashboard: the groups, then the categories inside one, then a single
 * category. Choosing a group or category refines the whole page to it ([onRefine], null clears).
 */
@Composable
internal fun TrafficCategoriesCard(loader: Loader<CategoryStats>, refinement: String?, onRefine: (String?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val d = loader.data
    CdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Solar.Shield, null, Modifier.size(20.dp), tint = Palette.Teal)
                Spacer(Modifier.width(8.dp))
                Text("Traffic Categories", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                (d?.refinedTotal ?: d?.total)?.let {
                    Text("${compact(it)} queries", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
                IconButton(onClick = { picking = true }, enabled = d != null) {
                    Icon(Solar.Filter, "Filter by category", tint = if (refinement != null) Palette.Teal else Palette.Muted)
                }
            }
            when {
                d == null && loader.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                d == null -> Text(loader.error ?: "Couldn't load categories", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                else -> CategoryViews(d, refinement, onRefine)
            }
        }
    }
    if (picking && d != null) {
        val choices = listOf<String?>(null) + d.tax.groups.map { GROUP_PREFIX + it.id } +
            d.counts.filterKeys { it != UNCLASSIFIED && d.tax.groupOf(it) != null }.entries.sortedByDescending { it.value }.map { it.key }
        PickerDialog(
            "Filter by category", choices,
            label = { c ->
                when {
                    c == null -> "All categories"
                    c.startsWith(GROUP_PREFIX) -> "Group: ${d.tax.label(c)}"
                    else -> "${d.tax.label(c)} (${compact(d.counts[c] ?: 0)})"
                }
            },
            selected = { it == refinement }, onDismiss = { picking = false },
        ) { onRefine(it) }
    }
}

@Composable
private fun CategoryViews(d: CategoryStats, refinement: String?, onRefine: (String?) -> Unit) {
    val tax = d.tax
    if (refinement == null) {
        Hint("One query can match several categories, so rows overlap and don't add up to 100%. Tap a group to look inside.")
        val groups = tax.groups.map { it to (d.groupCounts[it.id] ?: 0L) }.filter { it.second > 0 }.sortedByDescending { it.second }
        groups.forEach { (g, n) -> CategoryRow(g.name, n, d.total, chevron = true) { onRefine(GROUP_PREFIX + g.id) } }
        val unclassified = d.counts[UNCLASSIFIED] ?: 0L
        if (unclassified > 0) CategoryRow("Unclassified", unclassified, d.total, muted = true)
        if (groups.isEmpty() && unclassified == 0L) Hint("No queries in this period")
        return
    }
    val single = refinement.takeUnless { it.startsWith(GROUP_PREFIX) }
    val group = if (single != null) tax.groupOf(single) else tax.groups.firstOrNull { it.id == refinement.removePrefix(GROUP_PREFIX) }
    val refinedTotal = d.refinedTotal ?: 0L
    // ‹ All categories / Group: the group is a step back up when a single category is chosen.
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(cdShape(6)).clickable { onRefine(null) }.padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Solar.ChevronLeft, null, Modifier.size(16.dp), tint = Palette.Muted)
            Text("All categories", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        }
        if (group != null) {
            Text("  /  ", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Text(
                group.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (single != null) Modifier.clip(cdShape(6)).clickable { onRefine(GROUP_PREFIX + group.id) }.padding(vertical = 4.dp, horizontal = 2.dp) else Modifier,
            )
        }
    }
    if (single != null) {
        Hint("Filtered to one category. Remove the filter to compare categories.")
        CategoryRow(tax.label(single), refinedTotal, refinedTotal)
        return
    }
    Hint("Share of the ${compact(refinedTotal)} queries in this group. Rows overlap. Tap a category to filter by it.")
    val rows = group?.categoryIds.orEmpty().mapNotNull { id -> d.refinedCounts?.get(id)?.takeIf { it > 0 }?.let { id to it } }.sortedByDescending { it.second }
    rows.forEach { (id, n) -> CategoryRow(tax.label(id), n, refinedTotal) { onRefine(id) } }
    if (rows.isEmpty()) Hint("No queries in this group for this period")
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(bottom = 6.dp))
}

/** Name, compact count and share on one line, a thin bar under it (width = the share, as on the dashboard). */
@Composable
private fun CategoryRow(label: String, count: Long, of: Long, muted: Boolean = false, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    val share = if (of > 0) (count.toFloat() / of).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth().clip(cdShape(8))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (muted) Palette.Muted else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(compact(count), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (muted) Palette.Muted else Palette.Text)
            Text(sharePct(share), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterEnd) {
                if (chevron) Icon(Solar.ChevronRight, null, Modifier.size(18.dp), tint = Palette.Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CdPill).background(Palette.Outline)) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CdPill).background(if (muted) Palette.Muted else Palette.Teal))
        }
    }
}

internal fun sharePct(share: Float): String = when {
    share <= 0f -> "0%"
    share < 0.01f -> "<1%"
    else -> "${(share * 100).roundToInt()}%"
}
