package com.nativefinds.app.ui.finds

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import com.nativefinds.app.audio.isAudioPath
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.FindThumb
import com.nativefinds.app.ui.common.establishmentLabel
import com.nativefinds.app.ui.common.formatDate
import java.io.File

private enum class Kind(val label: String) { ALL("All"), BIRDS("Birds"), PLANTS("Plants"), SOUNDS("Sounds"), UNIDENTIFIED("Unidentified"), UNSURE("Needs review") }
private enum class Origin(val label: String) { ANY("Any status"), NATIVE("Native"), ENDEMIC("Endemic"), INTRODUCED("Introduced"), PESTS("Pests") }
private enum class When(val label: String, val days: Int?) { ANY("Any time", null), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last 12 months", 365) }
private enum class SortBy(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name A–Z"), PHOTOS("Most photos") }
private enum class View(val label: String) { LIST("Every find"), SPECIES("By species") }

/** One species in the "By species" view. */
private data class SpeciesGroup(val key: String, val name: String, val scientificName: String?, val finds: List<Find>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindsScreen(
    container: AppContainer,
    onNewFind: () -> Unit,
    onOpen: (Long) -> Unit,
    onStats: () -> Unit,
    onListen: () -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val finds by container.db.finds().observeAll().collectAsState(initial = null)
    val photos by container.db.finds().observeAllPhotos().collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var origin by rememberSaveable { mutableStateOf(Origin.ANY) }
    var period by rememberSaveable { mutableStateOf(When.ANY) }
    var sort by rememberSaveable { mutableStateOf(SortBy.NEWEST) }
    var view by rememberSaveable { mutableStateOf(View.LIST) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val photosByFind = remember(photos) { photos.groupBy { it.findId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My finds") },
                actions = { IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Stats and life list") } },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = onListen) { Icon(Icons.Filled.Mic, "Identify a bird by its song") }
                ExtendedFloatingActionButton(
                    onClick = onNewFind,
                    icon = { Icon(Icons.Filled.AddAPhoto, null) },
                    text = { Text("New find") },
                )
            }
        },
    ) { padding ->
        val list = finds
        when {
            list == null -> {}
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No finds yet. Tap New find, photograph a bird, leaf or flower, and it will be identified and saved here with its location.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> {
                val placeId = settings?.regionPlaceId ?: 6803
                val q = query.trim().lowercase()
                val now = System.currentTimeMillis()
                fun photoCount(f: Find) = photosByFind[f.id]?.count { !isAudioPath(it.path) } ?: (if (isAudioPath(f.photoPath)) 0 else 1)
                val filtered = list.asSequence()
                    .filter { q.isEmpty() || listOfNotNull(it.commonName, it.scientificName, it.notes).any { s -> s.lowercase().contains(q) } }
                    .filter {
                        when (kind) {
                            Kind.ALL -> true
                            Kind.BIRDS -> it.category == Category.BIRD.name
                            Kind.PLANTS -> runCatching { Category.valueOf(it.category).isPlant }.getOrDefault(false)
                            Kind.SOUNDS -> isAudioPath(it.photoPath) || photosByFind[it.id].orEmpty().any { p -> isAudioPath(p.path) }
                            Kind.UNIDENTIFIED -> it.scientificName == null
                            Kind.UNSURE -> it.scientificName == null || (it.idSource != "Entered by hand" && (it.confidence ?: 0f) < 0.5f)
                        }
                    }
                    .filter {
                        when (origin) {
                            Origin.ANY -> true
                            Origin.NATIVE -> it.establishment == "native" || it.establishment == "endemic"
                            Origin.ENDEMIC -> it.establishment == "endemic"
                            Origin.INTRODUCED -> it.establishment == "introduced"
                            Origin.PESTS -> container.pests.lookup(it.scientificName, it.regionPlaceId ?: placeId) != null
                        }
                    }
                    .filter { f -> period.days?.let { now - f.createdAt <= it * 86_400_000L } ?: true }
                    .toList()
                val shown = when (sort) {
                    SortBy.NEWEST -> filtered.sortedByDescending { it.createdAt }
                    SortBy.OLDEST -> filtered.sortedBy { it.createdAt }
                    SortBy.NAME -> filtered.sortedBy { (it.commonName ?: it.scientificName ?: "~").lowercase() }
                    SortBy.PHOTOS -> filtered.sortedByDescending { photoCount(it) }
                }
                val groups = remember(shown) {
                    shown.groupBy { f -> f.scientificName?.trim()?.split(Regex("\\s+"))?.take(2)?.joinToString(" ")?.lowercase() ?: "" }
                        .map { (k, fs) ->
                            SpeciesGroup(
                                key = k,
                                name = if (k.isEmpty()) "Unidentified" else fs.firstNotNullOfOrNull { it.commonName } ?: fs.first().scientificName!!,
                                scientificName = fs.firstNotNullOfOrNull { it.scientificName },
                                finds = fs,
                            )
                        }
                        .let { g ->
                            when (sort) {
                                SortBy.NAME -> g.sortedBy { it.name.lowercase() }
                                SortBy.PHOTOS -> g.sortedByDescending { grp -> grp.finds.sumOf { photoCount(it) } }
                                SortBy.OLDEST -> g.sortedBy { grp -> grp.finds.minOf { it.createdAt } }
                                SortBy.NEWEST -> g.sortedByDescending { grp -> grp.finds.maxOf { it.createdAt } }
                            }
                        }
                }
                val activeFilters = listOf(kind != Kind.ALL, origin != Origin.ANY, period != When.ANY).count { it }

                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search ${list.size} finds") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Chips(View.entries, view, { it.label }) { view = it }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { showFilters = !showFilters }) {
                                    Icon(Icons.Filled.FilterList, null)
                                    Text(if (activeFilters > 0) " Filter ($activeFilters)" else " Filter")
                                }
                            }
                            if (showFilters) {
                                Chips(Kind.entries, kind, { it.label }) { kind = it }
                                Chips(Origin.entries, origin, { it.label }) { origin = it }
                                Chips(When.entries, period, { it.label }) { period = it }
                                Chips(SortBy.entries, sort, { "Sort: " + it.label }) { sort = it }
                                if (activeFilters > 0) {
                                    TextButton(onClick = { kind = Kind.ALL; origin = Origin.ANY; period = When.ANY }) { Text("Clear filters") }
                                }
                            }
                            Text(
                                if (view == View.LIST) "${shown.size} of ${list.size} finds" else "${groups.size} species · ${shown.size} finds",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (view == View.LIST) {
                        items(shown, key = { it.id }) { f -> FindRow(f, photoCount(f), onClick = { onOpen(f.id) }) }
                    } else {
                        items(groups, key = { "g-" + it.key }) { g ->
                            SpeciesGroupRow(g, onClick = {
                                if (g.scientificName != null) onOpenSpecies(g.scientificName)
                                else { view = View.LIST; kind = Kind.UNIDENTIFIED }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SpeciesGroupRow(g: SpeciesGroup, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    if (g.scientificName != null && g.scientificName != g.name) {
                        Text(g.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                    }
                    val first = g.finds.minOf { it.createdAt }
                    val last = g.finds.maxOf { it.createdAt }
                    Text(
                        "${g.finds.size} find${if (g.finds.size == 1) "" else "s"} · " +
                            if (g.finds.size == 1) formatDate(last) else "${formatDate(first)} – ${formatDate(last)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                establishmentLabel(g.finds.firstNotNullOfOrNull { it.establishment })?.let { (t, c) ->
                    Text(t, color = c, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.finds.take(8).forEach { f -> FindThumb(f.photoPath, Modifier.size(56.dp)) }
            }
        }
    }
}

@Composable
private fun FindRow(f: Find, photoCount: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FindThumb(f.photoPath, Modifier.size(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.commonName ?: f.scientificName ?: "Unidentified", style = MaterialTheme.typography.titleMedium)
                if (f.commonName != null && f.scientificName != null) {
                    Text(f.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                }
                val cat = runCatching { Category.valueOf(f.category).label }.getOrDefault(f.category)
                val extra = buildList {
                    if (photoCount > 1) add("$photoCount photos")
                    if (isAudioPath(f.photoPath)) add("sound")
                }.joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
                Text("$cat · ${formatDate(f.createdAt)}$extra", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            establishmentLabel(f.establishment)?.let { (t, c) ->
                Text(t, color = c, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
