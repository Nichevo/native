package com.nativefinds.app.ui.places

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.BirdCount
import com.nativefinds.app.data.BirdCountEntry
import com.nativefinds.app.id.Category
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private data class Bird(val scientificName: String, val commonName: String?)

/**
 * A timed bird count. For each species you note the most seen at one time (not a running total),
 * the same way the NZ Garden Bird Survey counts, so counts can be compared year to year.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountScreen(container: AppContainer, placeId: Long?, onBack: () -> Unit, onSaved: () -> Unit) {
    val places by container.db.places().observeAll().collectAsState(initial = emptyList())
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    val regionId = settings?.regionPlaceId
    val pack by remember(regionId) {
        if (regionId == null) flowOf(emptyList()) else container.db.regions().observeSpecies(regionId)
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var chosenPlace by rememberSaveable { mutableStateOf(placeId ?: -1L) }
    var minutes by rememberSaveable { mutableStateOf(60) }
    var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var query by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val tally = remember { mutableStateMapOf<String, Int>() }
    val names = remember { mutableStateMapOf<String, Bird>() }

    // Keep the screen on while counting.
    val view = LocalView.current
    DisposableEffect(startedAt) {
        view.keepScreenOn = startedAt != null
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(startedAt) {
        while (startedAt != null) { now = System.currentTimeMillis(); delay(1000) }
    }

    val place = places.firstOrNull { it.id == chosenPlace }
    val packBirds = remember(pack) {
        pack.filter { it.iconicTaxon == "Aves" }.sortedByDescending { it.observations }.map { Bird(it.scientificName, it.commonName) }
    }
    // Birds you've found at this place first, then the region's most-recorded birds.
    val suggestions = remember(place, finds, packBirds) {
        val local = finds.filter { it.category == Category.BIRD.name && it.scientificName != null && (place == null || place.contains(it.latitude, it.longitude)) }
            .groupBy { it.scientificName!!.lowercase() }
            .entries.sortedByDescending { it.value.size }
            .map { (_, fs) -> Bird(fs.first().scientificName!!, fs.firstNotNullOfOrNull { it.commonName }) }
        (local + packBirds.take(30)).distinctBy { it.scientificName.lowercase() }
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) suggestions else packBirds.filter {
        it.scientificName.lowercase().contains(q) || (it.commonName?.lowercase()?.contains(q) ?: false)
    }.take(40)
    val counted = tally.filterValues { it > 0 }.keys.mapNotNull { names[it] }
    val elapsed = startedAt?.let { ((now - it) / 1000).toInt() } ?: 0
    val total = minutes * 60
    val timeUp = startedAt != null && elapsed >= total

    fun change(b: Bird, by: Int) {
        val k = b.scientificName.lowercase()
        names[k] = b
        tally[k] = ((tally[k] ?: 0) + by).coerceAtLeast(0)
        if (startedAt == null) {
            startedAt = System.currentTimeMillis()
            scope.launch { container.location.current()?.let { location = it.latitude to it.longitude } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bird count") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "For each species, count the most you can see at one time, not a running total. " +
                            "This is how the NZ Garden Bird Survey counts, so your counts compare year to year.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (startedAt == null) {
                        if (places.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = chosenPlace == -1L, onClick = { chosenPlace = -1L }, label = { Text("Not at a place") })
                                places.forEach { p -> FilterChip(selected = chosenPlace == p.id, onClick = { chosenPlace = p.id }, label = { Text(p.name) }) }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 20, 30, 60).forEach { m ->
                                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
                            }
                        }
                        Button(onClick = {
                            startedAt = System.currentTimeMillis()
                            scope.launch { container.location.current()?.let { location = it.latitude to it.longitude } }
                        }) { Text("Start the timer") }
                    } else {
                        val left = (total - elapsed).coerceAtLeast(0)
                        Text(
                            if (timeUp) "Time's up. Save your count below."
                            else "%d:%02d left · %s".format(left / 60, left % 60, place?.name ?: "not at a saved place"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LinearProgressIndicator(progress = { (elapsed.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (counted.isNotEmpty()) {
                item { Text("Counted: ${counted.size} species, ${tally.values.sum()} birds", style = MaterialTheme.typography.titleSmall) }
                items(counted.sortedBy { (it.commonName ?: it.scientificName).lowercase() }, key = { "c-" + it.scientificName }) { b ->
                    TallyRow(b, tally[b.scientificName.lowercase()] ?: 0) { change(b, it) }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find another bird") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (packBirds.isEmpty()) {
                    Text("Download your region pack in Settings to search all birds.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(shown.filter { (tally[it.scientificName.lowercase()] ?: 0) == 0 }, key = { "s-" + it.scientificName }) { b ->
                TallyRow(b, 0) { change(b, it) }
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (weather, anything unusual)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = counted.isNotEmpty() && !saving, onClick = {
                        saving = true
                        scope.launch {
                            val start = startedAt ?: System.currentTimeMillis()
                            val mins = if (timeUp) minutes else maxOf(1, (elapsed + 59) / 60)
                            val id = container.db.counts().insert(
                                BirdCount(
                                    placeId = place?.id,
                                    placeName = place?.name,
                                    startedAt = start,
                                    minutes = mins,
                                    latitude = location?.first ?: place?.latitude,
                                    longitude = location?.second ?: place?.longitude,
                                    notes = notes.trim(),
                                ),
                            )
                            container.db.counts().insertEntries(counted.map { b ->
                                BirdCountEntry(countId = id, scientificName = b.scientificName, commonName = b.commonName, count = tally[b.scientificName.lowercase()] ?: 0)
                            })
                            onSaved()
                        }
                    }) { Text("Save count") }
                    OutlinedButton(onClick = onBack) { Text("Discard") }
                }
            }
        }
    }
}

@Composable
private fun TallyRow(b: Bird, count: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.commonName ?: b.scientificName, style = MaterialTheme.typography.bodyLarge)
            if (b.commonName != null) Text(b.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
        }
        if (count > 0) {
            IconButton(onClick = { onChange(-1) }) { Icon(Icons.Filled.Remove, "One fewer") }
            Text(count.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalIconButton(onClick = { onChange(1) }) { Icon(Icons.Filled.Add, "One more") }
    }
}
