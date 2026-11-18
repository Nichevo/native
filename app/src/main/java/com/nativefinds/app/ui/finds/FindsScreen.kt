package com.nativefinds.app.ui.finds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.audio.isAudioPath
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.Place
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.FindThumb
import com.nativefinds.app.ui.common.establishmentLabel
import com.nativefinds.app.ui.common.formatDate
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Kind(val label: String) { ALL("All"), BIRDS("🐦 Birds"), PLANTS("🌿 Plants"), BUGS("🐞 Bugs"), LIZARDS("🦎 Lizards") }
private enum class Origin(val label: String) { ANY("Any"), NATIVE("Native"), ENDEMIC("Endemic only"), INTRODUCED("Introduced"), PESTS("Pests") }
private enum class Show(val label: String) {
    REVIEW("Needs review"), UNIDENTIFIED("Unidentified"), SOUND("Has sound"), MULTI("Multiple photos"), LIFER("New to my life list")
}
private enum class When(val label: String) { ANY("Any time"), TODAY("Today"), WEEK("7 days"), MONTH("30 days"), YEAR("This year"), RANGE("Pick dates…") }
private enum class SortBy(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name A–Z"), PHOTOS("Most photos"), UNSURE("Least sure first") }
private enum class View(val label: String) { LIST("☰ List"), SPECIES("🌳 By species") }

private const val EVERYWHERE = -1L
private const val NEAR_ME = -2L
private const val NEAR_ME_METRES = 1000f

/** One species in the "By species" view. */
private data class SpeciesGroup(val key: String, val name: String, val scientificName: String?, val finds: List<Find>)

/** A row in the list: a day heading or a find. */
private sealed interface Row2 {
    data class Heading(val label: String) : Row2
    data class Item(val find: Find) : Row2
}

private fun speciesKey(name: String?) = name?.trim()?.split(Regex("\\s+"))?.take(2)?.joinToString(" ")?.lowercase() ?: ""

private fun needsReview(f: Find) = f.scientificName == null || (f.idSource != "Entered by hand" && (f.confidence ?: 0f) < 0.5f)

private fun startOfDay(ms: Long, daysAgo: Int = 0): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    add(Calendar.DAY_OF_YEAR, -daysAgo)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(ms: Long, now: Long): String {
    val today = startOfDay(now)
    val day = startOfDay(ms)
    return when {
        day == today -> "Today"
        day == startOfDay(now, 1) -> "Yesterday"
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ->
            SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FindsScreen(
    container: AppContainer,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpen: (Long) -> Unit,
    onStats: () -> Unit,
    onListen: () -> Unit,
    onChorus: () -> Unit,
    onOpenSpecies: (String) -> Unit,
    onOpenPlace: (Long) -> Unit = {},
    onCount: () -> Unit = {},
) {
    val finds by container.db.finds().observeAll().collectAsState(initial = null)
    val photos by container.db.finds().observeAllPhotos().collectAsState(initial = emptyList())
    val places by container.db.places().observeAll().collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var origin by rememberSaveable { mutableStateOf(Origin.ANY) }
    var showCsv by rememberSaveable { mutableStateOf("") }
    val show = showCsv.split(',').mapNotNull { runCatching { Show.valueOf(it) }.getOrNull() }.toSet()
    fun setShow(s: Set<Show>) { showCsv = s.joinToString(",") { it.name } }
    var period by rememberSaveable { mutableStateOf(When.ANY) }
    var rangeStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var rangeEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var placeFilter by rememberSaveable { mutableStateOf(EVERYWHERE) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortBy.NEWEST) }
    var view by rememberSaveable { mutableStateOf(View.LIST) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    val photosByFind = remember(photos) { photos.groupBy { it.findId } }

    val list = finds
    val now = System.currentTimeMillis()
    val placeId = settings?.regionPlaceId ?: 6803
    fun photoCount(f: Find) = photosByFind[f.id]?.count { !isAudioPath(it.path) } ?: (if (isAudioPath(f.photoPath)) 0 else 1)
    fun hasSound(f: Find) = isAudioPath(f.photoPath) || photosByFind[f.id].orEmpty().any { isAudioPath(it.path) }
    fun placeOf(f: Find): Place? = places.firstOrNull { it.contains(f.latitude, f.longitude) }
    fun nearMe(f: Find): Boolean {
        val (lat, lng) = here ?: return false
        if (f.latitude == null || f.longitude == null) return false
        val out = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, f.latitude, f.longitude, out)
        return out[0] <= NEAR_ME_METRES
    }
    fun kindOf(f: Find): Kind = when (f.category) {
        Category.BIRD.name -> Kind.BIRDS
        Category.INSECT.name -> Kind.BUGS
        Category.LIZARD.name -> Kind.LIZARDS
        else -> if (runCatching { Category.valueOf(f.category).isPlant }.getOrDefault(false)) Kind.PLANTS else Kind.ALL
    }
    val firstOfSpecies = remember(list) {
        list.orEmpty().filter { it.scientificName != null }.groupBy { speciesKey(it.scientificName) }
            .values.map { fs -> fs.minBy { it.createdAt }.id }.toSet()
    }

    // Everything except the type chips, so the chips can show counts for the current filters.
    val baseFiltered = run {
        val q = query.trim().lowercase()
        val place = places.firstOrNull { it.id == placeFilter }
        list.orEmpty().filter { f ->
            (q.isEmpty() || listOfNotNull(f.commonName, f.scientificName, f.notes).any { it.lowercase().contains(q) }) &&
                when (origin) {
                    Origin.ANY -> true
                    Origin.NATIVE -> f.establishment == "native" || f.establishment == "endemic"
                    Origin.ENDEMIC -> f.establishment == "endemic"
                    Origin.INTRODUCED -> f.establishment == "introduced"
                    Origin.PESTS -> container.pests.lookup(f.scientificName, f.regionPlaceId ?: placeId) != null
                } &&
                show.all { s ->
                    when (s) {
                        Show.REVIEW -> needsReview(f)
                        Show.UNIDENTIFIED -> f.scientificName == null
                        Show.SOUND -> hasSound(f)
                        Show.MULTI -> photoCount(f) > 1
                        Show.LIFER -> f.id in firstOfSpecies
                    }
                } &&
                when (period) {
                    When.ANY -> true
                    When.TODAY -> f.createdAt >= startOfDay(now)
                    When.WEEK -> f.createdAt >= startOfDay(now, 6)
                    When.MONTH -> f.createdAt >= startOfDay(now, 29)
                    When.YEAR -> Calendar.getInstance().apply { timeInMillis = f.createdAt }.get(Calendar.YEAR) ==
                        Calendar.getInstance().get(Calendar.YEAR)
                    When.RANGE -> (rangeStart == null || f.createdAt >= startOfDay(rangeStart!!)) &&
                        (rangeEnd == null || f.createdAt < startOfDay(rangeEnd!!) + 86_400_000L)
                } &&
                when (placeFilter) {
                    EVERYWHERE -> true
                    NEAR_ME -> nearMe(f)
                    else -> place?.contains(f.latitude, f.longitude) ?: true
                }
        }
    }
    val filtered = if (kind == Kind.ALL) baseFiltered else baseFiltered.filter { kindOf(it) == kind }
    val shown = when (sort) {
        SortBy.NEWEST -> filtered.sortedByDescending { it.createdAt }
        SortBy.OLDEST -> filtered.sortedBy { it.createdAt }
        SortBy.NAME -> filtered.sortedBy { (it.commonName ?: it.scientificName ?: "~").lowercase() }
        SortBy.PHOTOS -> filtered.sortedByDescending { photoCount(it) }
        SortBy.UNSURE -> filtered.sortedBy { if (it.scientificName == null) -1f else it.confidence ?: 1f }
    }
    val groupByDay = sort == SortBy.NEWEST || sort == SortBy.OLDEST
    val rows: List<Row2> = if (!groupByDay) shown.map { Row2.Item(it) } else buildList {
        var last: String? = null
        shown.forEach { f ->
            val label = dayLabel(f.createdAt, now)
            if (label != last) { add(Row2.Heading(label)); last = label }
            add(Row2.Item(f))
        }
    }
    val groups = shown.groupBy { speciesKey(it.scientificName) }.map { (k, fs) ->
        SpeciesGroup(
            key = k,
            name = if (k.isEmpty()) "Unidentified" else fs.firstNotNullOfOrNull { it.commonName } ?: fs.first().scientificName!!,
            scientificName = fs.firstNotNullOfOrNull { it.scientificName },
            finds = fs,
        )
    }.let { g ->
        when (sort) {
            SortBy.NAME -> g.sortedBy { it.name.lowercase() }
            SortBy.PHOTOS -> g.sortedByDescending { grp -> grp.finds.sumOf { photoCount(it) } }
            SortBy.OLDEST -> g.sortedBy { grp -> grp.finds.minOf { it.createdAt } }
            SortBy.UNSURE -> g.sortedBy { grp -> grp.finds.minOf { it.confidence ?: 1f } }
            SortBy.NEWEST -> g.sortedByDescending { grp -> grp.finds.maxOf { it.createdAt } }
        }
    }
    val activeCount = listOf(origin != Origin.ANY, period != When.ANY, placeFilter != EVERYWHERE).count { it } + show.size
    fun clearAll() {
        origin = Origin.ANY; setShow(emptySet()); period = When.ANY; rangeStart = null; rangeEnd = null
        placeFilter = EVERYWHERE; sort = SortBy.NEWEST
    }
    fun pickPlace(id: Long) {
        placeFilter = id
        if (id == NEAR_ME) scope.launch { container.location.current()?.let { here = it.latitude to it.longitude } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My finds") },
                actions = {
                    IconButton(onClick = onCount) { Icon(Icons.Filled.Visibility, "Start a bird count") }
                    IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Stats and life list") }
                },
            )
        },
        bottomBar = { ActionBar(onCamera, onGallery, onListen, onChorus) },
    ) { padding ->
        if (list == null) return@Scaffold
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Quick numbers.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val week = list.count { it.createdAt >= startOfDay(now, 6) }
                        val species = list.mapNotNull { it.scientificName }.map(::speciesKey).toSet().size
                        val review = list.count(::needsReview)
                        Stat("${list.size}", "finds", Modifier.weight(1f)) { clearAll(); kind = Kind.ALL; view = View.LIST }
                        Stat("$species", "species", Modifier.weight(1f)) { view = View.SPECIES }
                        Stat("$week", "this week", Modifier.weight(1f)) { period = When.WEEK; view = View.LIST }
                        Stat("$review", "to review", Modifier.weight(1f), highlight = review > 0) {
                            setShow(setOf(Show.REVIEW)); view = View.LIST; sort = SortBy.UNSURE
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search finds, species, notes") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            TextButton(onClick = { sheetOpen = true }) {
                                Icon(Icons.Filled.FilterList, null)
                                Text(if (activeCount > 0) " Filter ($activeCount)" else " Filter")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Type chips with counts.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kind.entries.forEach { k ->
                            val n = if (k == Kind.ALL) baseFiltered.size else baseFiltered.count { kindOf(it) == k }
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text("${k.label}  $n") })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (places.isNotEmpty()) {
                            FilterChip(selected = placeFilter == EVERYWHERE, onClick = { pickPlace(EVERYWHERE) }, label = { Text("📍 Everywhere") })
                            places.forEach { p ->
                                FilterChip(
                                    selected = placeFilter == p.id,
                                    onClick = { pickPlace(if (placeFilter == p.id) EVERYWHERE else p.id) },
                                    label = { Text("🏡 ${p.name}  ${list.count { f -> p.contains(f.latitude, f.longitude) }}") },
                                )
                            }
                        }
                        View.entries.forEach { v -> FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) }) }
                    }
                    places.firstOrNull { it.id == placeFilter }?.let { p ->
                        TextButton(onClick = { onOpenPlace(p.id) }) { Text("${p.name}: through the year and bird counts") }
                    }
                    // Active filters, each removable.
                    val active = buildList<Pair<String, () -> Unit>> {
                        if (origin != Origin.ANY) add(origin.label to { origin = Origin.ANY })
                        show.forEach { s -> add(s.label to { setShow(show - s) }) }
                        if (period == When.RANGE) {
                            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
                            add("${rangeStart?.let { df.format(Date(it)) } ?: "…"} – ${rangeEnd?.let { df.format(Date(it)) } ?: "…"}" to {
                                period = When.ANY; rangeStart = null; rangeEnd = null
                            })
                        } else if (period != When.ANY) add(period.label to { period = When.ANY })
                        if (placeFilter == NEAR_ME) add("Near me now" to { placeFilter = EVERYWHERE })
                        if (sort != SortBy.NEWEST) add("Sort: ${sort.label}" to { sort = SortBy.NEWEST })
                    }
                    if (active.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            active.forEach { (label, remove) ->
                                InputChip(
                                    selected = true,
                                    onClick = remove,
                                    label = { Text(label) },
                                    trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        Text(
                            "No finds yet. Tap Camera to photograph a bird, leaf, flower or bug, or Listen to identify a bird by its song. " +
                                "Each find is saved with where and when you found it.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else if (shown.isEmpty()) {
                        Column {
                            Text("Nothing matches these filters.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { clearAll(); kind = Kind.ALL; query = "" }) { Text("Clear filters") }
                        }
                    } else if (view == View.SPECIES) {
                        Text(
                            "${groups.size} species · ${shown.size} finds",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            if (view == View.LIST) {
                items(rows, key = { r -> when (r) { is Row2.Heading -> "h-" + r.label; is Row2.Item -> r.find.id } }) { r ->
                    when (r) {
                        is Row2.Heading -> Text(
                            r.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        is Row2.Item -> FindRow(r.find, photoCount(r.find), hasSound(r.find), placeOf(r.find)?.name, timeOnly = groupByDay) {
                            onOpen(r.find.id)
                        }
                    }
                }
            } else {
                items(groups, key = { "g-" + it.key }) { g ->
                    SpeciesGroupRow(g, onClick = {
                        if (g.scientificName != null) onOpenSpecies(g.scientificName)
                        else { view = View.LIST; setShow(show + Show.UNIDENTIFIED) }
                    })
                }
            }
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filter and sort", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clearAll() }) { Text("Clear all") }
                }
                SheetHeading("Status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Origin.entries.forEach { o -> FilterChip(selected = origin == o, onClick = { origin = o }, label = { Text(o.label) }) }
                }
                SheetHeading("Show")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Show.entries.forEach { s ->
                        val n = when (s) {
                            Show.REVIEW -> list.orEmpty().count(::needsReview)
                            Show.UNIDENTIFIED -> list.orEmpty().count { it.scientificName == null }
                            else -> null
                        }
                        FilterChip(
                            selected = s in show,
                            onClick = { setShow(if (s in show) show - s else show + s) },
                            label = { Text(s.label + (n?.let { "  $it" } ?: "")) },
                        )
                    }
                }
                SheetHeading("When")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    When.entries.forEach { w ->
                        FilterChip(selected = period == w, onClick = {
                            if (w == When.RANGE) pickingDates = true else period = w
                        }, label = { Text(w.label) })
                    }
                }
                SheetHeading("Where")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = placeFilter == EVERYWHERE, onClick = { pickPlace(EVERYWHERE) }, label = { Text("Everywhere") })
                    FilterChip(selected = placeFilter == NEAR_ME, onClick = { pickPlace(NEAR_ME) }, label = { Text("Near me now") })
                    places.forEach { p -> FilterChip(selected = placeFilter == p.id, onClick = { pickPlace(p.id) }, label = { Text(p.name) }) }
                }
                if (placeFilter == NEAR_ME) {
                    Text(
                        if (here == null) "Finding where you are…" else "Within 1 km of where you are now.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SheetHeading("Sort")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortBy.entries.forEach { o -> FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.label) }) }
                }
                Button(
                    onClick = { scope.launch { sheetState.hide(); sheetOpen = false } },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Show ${filtered.size} find${if (filtered.size == 1) "" else "s"}") }
            }
        }
    }

    if (pickingDates) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = rangeStart, initialSelectedEndDateMillis = rangeEnd)
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    rangeStart = state.selectedStartDateMillis
                    rangeEnd = state.selectedEndDateMillis ?: state.selectedStartDateMillis
                    period = When.RANGE
                    pickingDates = false
                }) { Text("Use these dates") }
            },
            dismissButton = { TextButton(onClick = { pickingDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f), title = { Text("Finds between", Modifier.padding(16.dp)) })
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** The three one-tap actions: camera, gallery, and listen (long-press for dawn chorus). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionBar(onCamera: () -> Unit, onGallery: () -> Unit, onListen: () -> Unit, onChorus: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BigAction("Camera", Icons.Filled.CameraAlt, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
                Modifier.weight(1.4f), onCamera, null)
            BigAction("Gallery", Icons.Filled.Image, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                Modifier.weight(1f), onGallery, null)
            BigAction("Listen", Icons.Filled.Mic, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                Modifier.weight(1f), onListen, onChorus)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = label,
                onLongClickLabel = if (onLongClick != null) "Dawn chorus" else null)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (onLongClick != null) Text("hold: dawn chorus", style = MaterialTheme.typography.labelSmall)
        }
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
private fun FindRow(f: Find, photoCount: Int, sound: Boolean, placeName: String?, timeOnly: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FindThumb(f.photoPath, Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.commonName ?: f.scientificName ?: "Unidentified", style = MaterialTheme.typography.titleMedium)
                if (f.commonName != null && f.scientificName != null) {
                    Text(f.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                }
                val cat = if (isAudioPath(f.photoPath)) "Song" else runCatching { Category.valueOf(f.category).label }.getOrDefault(f.category)
                val time = if (timeOnly) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(f.createdAt)) else formatDate(f.createdAt)
                val parts = buildList {
                    add(cat); add(time)
                    if (photoCount > 1) add("$photoCount photos")
                    if (sound && !isAudioPath(f.photoPath)) add("sound")
                    placeName?.let { add(it) }
                    if (f.scientificName != null) f.confidence?.let { if (f.idSource != "Entered by hand") add("${(it * 100).toInt()}%") }
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (needsReview(f)) {
                    Text("Needs review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            establishmentLabel(f.establishment)?.let { (t, c) ->
                Text(t, color = c, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
