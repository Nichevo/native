package com.nativefinds.app.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nativefinds.app.info.NearbySpecies
import com.nativefinds.app.ui.common.PestRed
import com.nativefinds.app.ui.common.establishmentLabel

private enum class Radius(val km: Int, val label: String) { NEAR(1, "1 km"), LOCAL(5, "5 km"), AREA(25, "25 km") }
private enum class Period(val days: Int?, val label: String) {
    MONTH(30, "Last 30 days"), SEASON(90, "Last 3 months"), YEAR(365, "Last year"), ALL(null, "All time")
}
private enum class Group(val label: String) { ALL("All"), BIRDS("Birds"), PLANTS("Plants"), BUGS("Bugs"), LIZARDS("Lizards"), NOT_FOUND("Not found yet") }

/** What other people have recorded (iNaturalist, research grade) around where you are now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(container: AppContainer, onBack: () -> Unit, onOpenSpecies: (String) -> Unit) {
    val settings by container.settings.settings.collectAsState(initial = null)
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    var radius by rememberSaveable { mutableStateOf(Radius.LOCAL) }
    var period by rememberSaveable { mutableStateOf(Period.SEASON) }
    var group by rememberSaveable { mutableStateOf(Group.ALL) }
    var list by remember { mutableStateOf<List<NearbySpecies>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(radius, period, settings?.regionPlaceId, settings?.offlineMode) {
        val s = settings ?: return@LaunchedEffect
        if (s.offlineMode) { error = "Nearby sightings need the internet. Turn off offline mode to use them."; return@LaunchedEffect }
        loading = true; error = null
        val loc = container.location.current()
        if (loc == null) {
            error = "Couldn't get your location. Check location permission and GPS."
        } else {
            runCatching { container.inat.nearbySpecies(loc.latitude, loc.longitude, radius.km, period.days, s.regionPlaceId) }
                .onSuccess { list = it }
                .onFailure { error = "Couldn't load sightings: ${it.message}" }
        }
        loading = false
    }

    val taxa = remember(finds) { finds.mapNotNull { it.taxonId }.toSet() }
    val names = remember(finds) { finds.mapNotNull { it.scientificName?.let(::key) }.toSet() }
    fun found(n: NearbySpecies) = n.taxonId in taxa || key(n.scientificName) in names
    val regionalStatus = remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    LaunchedEffect(list, settings?.regionPlaceId) {
        val placeId = settings?.regionPlaceId ?: return@LaunchedEffect
        val l = list ?: return@LaunchedEffect
        regionalStatus.value = l.associate { n -> n.taxonId to container.db.regions().findSpecies(placeId, n.scientificName)?.establishment }
    }
    val shown = list.orEmpty().filter {
        when (group) {
            Group.ALL -> true
            Group.BIRDS -> it.iconicTaxon == "Aves"
            Group.PLANTS -> it.iconicTaxon == "Plantae"
            Group.BUGS -> it.iconicTaxon == "Insecta" || it.iconicTaxon == "Arachnida"
            Group.LIZARDS -> it.iconicTaxon == "Reptilia"
            Group.NOT_FOUND -> !found(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seen near you") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Birds and plants other people have recorded on iNaturalist around you. Ticked ones you've found yourself.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Chips(Radius.entries, radius, { it.label }) { radius = it }
                    Chips(Period.entries, period, { it.label }) { period = it }
                    Chips(Group.entries, group, { it.label }) { group = it }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    list?.let { l ->
                        val f = l.count { found(it) }
                        Text("${l.size} species · you've found $f · ${shown.size} shown", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            items(shown, key = { it.taxonId }) { n ->
                val pest = settings?.let { container.pests.lookup(n.scientificName, it.regionPlaceId) }
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSpecies(n.scientificName) },
                    leadingContent = {
                        if (n.photoUrl != null) {
                            AsyncImage(
                                model = n.photoUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(Modifier.size(52.dp))
                        }
                    },
                    headlineContent = { Text(n.commonName ?: n.scientificName) },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${n.count} record${if (n.count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                            if (n.commonName != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(n.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                            }
                            establishmentLabel(regionalStatus.value[n.taxonId])?.let { (t, c) ->
                                Spacer(Modifier.width(6.dp))
                                Text(t, color = c, style = MaterialTheme.typography.labelSmall)
                            }
                            if (pest != null) {
                                Spacer(Modifier.width(6.dp))
                                Text("Pest", color = PestRed, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    trailingContent = {
                        if (found(n)) {
                            Icon(Icons.Filled.CheckCircle, "Found", tint = Color(0xFF2E7D32))
                        } else {
                            Icon(Icons.Outlined.Circle, "Not found yet", tint = MaterialTheme.colorScheme.outlineVariant)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun key(name: String): String = name.trim().split(Regex("\\s+")).take(2).joinToString(" ").lowercase()

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

