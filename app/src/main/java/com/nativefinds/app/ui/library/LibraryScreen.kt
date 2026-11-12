package com.nativefinds.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.RegionSpecies
import com.nativefinds.app.ui.common.PestRed
import com.nativefinds.app.ui.common.establishmentLabel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class Group(val label: String) { ALL("All"), BIRDS("Birds"), PLANTS("Plants") }
private enum class Status(val label: String) { ALL("Any status"), ENDEMIC("Endemic"), NATIVE("Native"), INTRODUCED("Introduced"), PESTS("Pests") }
private enum class Seen(val label: String) { ALL("All"), FOUND("Found"), NOT_YET("Not yet") }
private enum class Sort(val label: String) { COMMON("Most seen"), NAME("A–Z") }

/** "Genus species subspecies" -> "genus species", for matching finds against the list. */
internal fun speciesKey(name: String): String =
    name.trim().split(Regex("\\s+")).take(2).joinToString(" ").lowercase()

internal fun foundKeys(finds: List<Find>): Pair<Set<Long>, Set<String>> =
    finds.mapNotNull { it.taxonId }.toSet() to finds.mapNotNull { it.scientificName?.let(::speciesKey) }.toSet()

internal fun RegionSpecies.isFound(keys: Pair<Set<Long>, Set<String>>): Boolean =
    taxonId in keys.first || speciesKey(scientificName) in keys.second

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, onOpenSpecies: (String) -> Unit, onNearby: () -> Unit) {
    val settings by container.settings.settings.collectAsState(initial = null)
    val placeId = settings?.regionPlaceId
    val species by remember(placeId) {
        if (placeId == null) flowOf(emptyList()) else container.db.regions().observeSpecies(placeId)
    }.collectAsState(initial = null)
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(Group.ALL) }
    var status by rememberSaveable { mutableStateOf(Status.ALL) }
    var seen by rememberSaveable { mutableStateOf(Seen.ALL) }
    var sort by rememberSaveable { mutableStateOf(Sort.COMMON) }

    val keys = remember(finds) { foundKeys(finds) }
    val all = species
    val inGroup = remember(all, group) {
        all.orEmpty().filter {
            when (group) {
                Group.ALL -> true
                Group.BIRDS -> it.iconicTaxon == "Aves"
                Group.PLANTS -> it.iconicTaxon == "Plantae"
            }
        }
    }
    val isPest: (RegionSpecies) -> Boolean = { s -> container.pests.lookup(s.scientificName, s.placeId) != null }
    val shown = remember(inGroup, status, seen, sort, query, keys) {
        val q = query.trim().lowercase()
        inGroup.asSequence()
            .filter {
                when (status) {
                    Status.ALL -> true
                    Status.ENDEMIC -> it.establishment == "endemic"
                    Status.NATIVE -> it.establishment == "native" || it.establishment == "endemic"
                    Status.INTRODUCED -> it.establishment == "introduced"
                    Status.PESTS -> isPest(it)
                }
            }
            .filter {
                when (seen) {
                    Seen.ALL -> true
                    Seen.FOUND -> it.isFound(keys)
                    Seen.NOT_YET -> !it.isFound(keys)
                }
            }
            .filter { q.isEmpty() || it.scientificName.lowercase().contains(q) || it.commonName?.lowercase()?.contains(q) == true }
            .let { seq ->
                when (sort) {
                    Sort.COMMON -> seq.sortedByDescending { it.observations }
                    Sort.NAME -> seq.sortedBy { (it.commonName ?: it.scientificName).lowercase() }
                }
            }
            .toList()
    }
    val foundInGroup = remember(inGroup, keys) { inGroup.count { it.isFound(keys) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Species · ${settings?.regionName ?: ""}") },
                actions = {
                    TextButton(onClick = onNearby) {
                        Icon(Icons.Filled.NearMe, null)
                        Text(" Near me")
                    }
                },
            )
        },
    ) { padding ->
        when {
            all == null || settings == null -> {}
            all.isEmpty() -> NoPack(container, padding, settings!!.regionPlaceId, settings!!.regionName)
            else -> LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "You've found $foundInGroup of ${inGroup.size} ${group.label.lowercase().takeIf { group != Group.ALL } ?: "species"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LinearProgressIndicator(
                            progress = { if (inGroup.isEmpty()) 0f else foundInGroup.toFloat() / inGroup.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search names") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ChipRow(Group.entries, group, { it.label }) { group = it }
                        ChipRow(Seen.entries, seen, { it.label }) { seen = it }
                        ChipRow(Status.entries, status, { it.label }) { status = it }
                        if (status != Status.ALL && status != Status.PESTS && all.none { it.establishment != null }) {
                            Text(
                                "This pack has no native status yet. Tap Update pack in Settings to add it.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        ChipRow(Sort.entries, sort, { "Sort: " + it.label }) { sort = it }
                        Text("${shown.size} shown", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(shown, key = { it.taxonId }) { s ->
                    SpeciesRow(s, s.isFound(keys), isPest(s)) { onOpenSpecies(s.scientificName) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SpeciesRow(s: RegionSpecies, found: Boolean, pest: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = {
            if (s.photoUrl != null) {
                AsyncImage(
                    model = s.photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(Modifier.size(52.dp))
            }
        },
        headlineContent = { Text(s.commonName ?: s.scientificName) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.commonName != null) Text(s.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                establishmentLabel(s.establishment)?.let { (t, c) ->
                    Spacer(Modifier.width(6.dp))
                    Text(t, color = c, style = MaterialTheme.typography.labelSmall)
                }
                if (pest) {
                    Spacer(Modifier.width(6.dp))
                    Text("Pest", color = PestRed, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        trailingContent = {
            if (found) {
                Icon(Icons.Filled.CheckCircle, "Found", tint = Color(0xFF2E7D32))
            } else {
                Icon(Icons.Outlined.Circle, "Not found yet", tint = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    )
}

@Composable
private fun NoPack(container: AppContainer, padding: PaddingValues, placeId: Int, regionName: String) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.padding(padding).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Download the $regionName pack to see every bird and plant recorded there, and tick them off as you find them.")
        Text("It takes about a minute and works offline afterwards.", style = MaterialTheme.typography.bodySmall)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = progress == null, onClick = {
            scope.launch {
                progress = 0f; error = null
                runCatching {
                    container.regions.download(placeId, regionName) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                    }
                }.onFailure { error = "Download failed: ${it.message}" }
                progress = null
            }
        }) { Text("Download $regionName pack") }
    }
}
