package com.nativefinds.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.BirdCount
import com.nativefinds.app.data.BirdCountEntry
import com.nativefinds.app.ui.common.formatDate
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val MONTHS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

/** One species' record at a place, from finds and bird counts. */
private data class CalendarRow(
    val key: String,
    val name: String,
    val scientificName: String,
    val months: Set<Int>,
    val first: Long,
    val last: Long,
    val finds: Int,
    val counted: Int,
)

/** A saved place's page: when each species turns up through the year, and its bird counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceScreen(
    container: AppContainer,
    placeId: Long,
    onBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
    onStartCount: (Long) -> Unit,
) {
    val place by container.db.places().observe(placeId).collectAsState(initial = null)
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    val counts by container.db.counts().observeAll().collectAsState(initial = emptyList())
    val entries by container.db.counts().observeAllEntries().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var openCount by remember { mutableStateOf<Long?>(null) }

    val p = place
    val here = remember(p, finds) { if (p == null) emptyList() else finds.filter { p.contains(it.latitude, it.longitude) } }
    val myCounts = remember(counts, placeId) { counts.filter { it.placeId == placeId } }
    val entriesByCount = remember(entries) { entries.groupBy { it.countId } }
    val rows = remember(here, myCounts, entriesByCount) { buildRows(here, myCounts, entriesByCount) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p?.name ?: "Place") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (p == null) return@Scaffold
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${rows.size} species · ${here.size} finds · ${myCounts.size} bird counts · within ${sizeLabel(p.radiusMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = { onStartCount(placeId) }) {
                    Icon(Icons.Filled.Visibility, null); Text("  Start a bird count here")
                }
            }
            item {
                Text("Through the year", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Each square is a month you've seen it here, in any year, from your finds and bird counts.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(top = 6.dp)) {
                    Spacer(Modifier.weight(1f))
                    MONTHS.forEach { m ->
                        Text(m, Modifier.width(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text("Nothing here yet. Finds you make inside this place, and bird counts, will show up here.") }
            }
            items(rows, key = { "r-" + it.key }) { r -> CalendarRowView(r) { onOpenSpecies(r.scientificName) } }

            if (myCounts.isNotEmpty()) {
                item {
                    Spacer(Modifier.size(8.dp))
                    Text("Bird counts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(myCounts, key = { "c-" + it.id }) { c ->
                    val es = entriesByCount[c.id].orEmpty().sortedByDescending { it.count }
                    Card(onClick = { openCount = if (openCount == c.id) null else c.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatDate(c.startedAt), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${c.minutes} min · ${es.size} species · ${es.sumOf { it.count }} birds",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (openCount == c.id) {
                                    IconButton(onClick = { scope.launch { container.db.counts().delete(c) } }) {
                                        Icon(Icons.Filled.Delete, "Delete this count")
                                    }
                                }
                            }
                            if (openCount == c.id) {
                                es.forEach { e ->
                                    Row {
                                        Text(e.commonName ?: e.scientificName, Modifier.weight(1f))
                                        Text(e.count.toString())
                                    }
                                }
                                if (c.notes.isNotBlank()) Text(c.notes, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    es.take(5).joinToString { "${it.commonName ?: it.scientificName} ${it.count}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarRowView(r: CalendarRow, onClick: () -> Unit) {
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                if (r.name != r.scientificName) {
                    Text(r.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                }
            }
            (0 until 12).forEach { m ->
                Box(
                    Modifier.padding(1.dp).size(14.dp).clip(RoundedCornerShape(3.dp)).background(
                        if (m in r.months) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
        val seen = if (r.first == r.last) "Seen ${df.format(Date(r.first))}" else "First ${df.format(Date(r.first))} · last ${df.format(Date(r.last))}"
        val tally = buildList {
            if (r.finds > 0) add("${r.finds} find${if (r.finds == 1) "" else "s"}")
            if (r.counted > 0) add("in ${r.counted} count${if (r.counted == 1) "" else "s"}")
        }.joinToString(" · ")
        Text("$seen · $tally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

private fun monthOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.MONTH)

private fun keyOf(name: String) = name.trim().lowercase().split(Regex("\\s+")).take(2).joinToString(" ")

private fun buildRows(
    finds: List<com.nativefinds.app.data.Find>,
    counts: List<BirdCount>,
    entries: Map<Long, List<BirdCountEntry>>,
): List<CalendarRow> {
    data class Seen(val at: Long, val sci: String, val common: String?, val fromCount: Boolean)
    val seen = buildList {
        finds.forEach { f -> f.scientificName?.let { add(Seen(f.createdAt, it, f.commonName, false)) } }
        counts.forEach { c -> entries[c.id].orEmpty().filter { it.count > 0 }.forEach { add(Seen(c.startedAt, it.scientificName, it.commonName, true)) } }
    }
    return seen.groupBy { keyOf(it.sci) }.map { (k, list) ->
        val sci = list.first().sci
        CalendarRow(
            key = k,
            name = list.firstNotNullOfOrNull { it.common } ?: sci,
            scientificName = sci,
            months = list.map { monthOf(it.at) }.toSet(),
            first = list.minOf { it.at },
            last = list.maxOf { it.at },
            finds = list.count { !it.fromCount },
            counted = list.count { it.fromCount },
        )
    }.sortedWith(compareByDescending<CalendarRow> { it.months.size }.thenBy { it.name.lowercase() })
}
