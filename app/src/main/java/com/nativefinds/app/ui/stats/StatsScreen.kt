package com.nativefinds.app.ui.stats

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

/** One species on the life list: first time you found it, and how often since. */
data class LifeEntry(val key: String, val name: String, val scientificName: String, val first: Find, val count: Int, val isBird: Boolean, val category: String)

data class Hotspot(val lat: Double, val lng: Double, val finds: Int, val species: Int, val topNames: List<String>)

fun yearOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

private fun speciesKey(f: Find): String? = f.scientificName?.trim()?.split(Regex("\\s+"))?.take(2)?.joinToString(" ")?.lowercase()

fun lifeList(finds: List<Find>): List<LifeEntry> =
    finds.filter { speciesKey(it) != null }
        .groupBy { speciesKey(it)!! }
        .map { (k, fs) ->
            val first = fs.minBy { it.createdAt }
            LifeEntry(
                key = k,
                name = fs.firstNotNullOfOrNull { it.commonName } ?: first.scientificName!!,
                scientificName = first.scientificName!!,
                first = first,
                count = fs.size,
                isBird = first.category == "BIRD",
                category = first.category,
            )
        }
        .sortedByDescending { it.first.createdAt }

/** Groups located finds into ~1 km cells, busiest first. */
fun hotspots(finds: List<Find>, cellDeg: Double = 0.01): List<Hotspot> =
    finds.filter { it.latitude != null && it.longitude != null }
        .groupBy { (it.latitude!! / cellDeg).roundToInt() to (it.longitude!! / cellDeg).roundToInt() }
        .map { (_, fs) ->
            val names = fs.mapNotNull { it.commonName ?: it.scientificName }
            Hotspot(
                lat = fs.map { it.latitude!! }.average(),
                lng = fs.map { it.longitude!! }.average(),
                finds = fs.size,
                species = fs.mapNotNull { speciesKey(it) }.toSet().size,
                topNames = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key },
            )
        }
        .sortedWith(compareByDescending<Hotspot> { it.species }.thenByDescending { it.finds })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit, onOpenSpecies: (String) -> Unit) {
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    val life = remember(finds) { lifeList(finds) }
    val years = remember(finds) { finds.map { yearOf(it.createdAt) }.distinct().sortedDescending() }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    val spots = remember(finds) { hotspots(finds).take(10) }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (finds.isEmpty()) {
                Text("No finds yet. Your life list, yearly totals and hotspots will appear here.")
                return@Column
            }
            val nativeSpecies = life.count { e ->
                finds.any { speciesKey(it) == e.key && (it.establishment == "native" || it.establishment == "endemic") }
            }
            val endemicSpecies = life.count { e -> finds.any { speciesKey(it) == e.key && it.establishment == "endemic" } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Finds", finds.size.toString(), Modifier.weight(1f))
                Tile("Species", life.size.toString(), Modifier.weight(1f))
                Tile("Native", nativeSpecies.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Birds", life.count { it.isBird }.toString(), Modifier.weight(1f))
                Tile("Plants", life.count { e -> runCatching { com.nativefinds.app.id.Category.valueOf(e.category).isPlant }.getOrDefault(false) }.toString(), Modifier.weight(1f))
                Tile("Endemic", endemicSpecies.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Bugs", life.count { it.category == "INSECT" }.toString(), Modifier.weight(1f))
                Tile("Lizards", life.count { it.category == "LIZARD" }.toString(), Modifier.weight(1f))
                Tile("Sounds", finds.count { it.photoPath.endsWith(".wav", true) }.toString(), Modifier.weight(1f))
            }

            Text("By year", style = MaterialTheme.typography.titleMedium)
            val perYear = years.map { y ->
                val fs = finds.filter { yearOf(it.createdAt) == y }
                Triple(y, fs.mapNotNull { speciesKey(it) }.toSet().size, life.count { yearOf(it.first.createdAt) == y })
            }
            val maxSpecies = perYear.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            perYear.forEach { (y, species, newOnes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$y", Modifier.width(52.dp))
                    Box(Modifier.weight(1f).height(18.dp)) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(species.toFloat() / maxSpecies)
                                .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        )
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(newOnes.toFloat() / maxSpecies)
                                .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text("  $species species, $newOnes new", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Dark bar = new to your life list that year.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

            HorizontalDivider()
            Text("Life list", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = year == null, onClick = { year = null }, label = { Text("All") })
                years.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text("New in $y") }) }
            }
            life.filter { year == null || yearOf(it.first.createdAt) == year }.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenSpecies(e.scientificName) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        if (e.name != e.scientificName) {
                            Text(e.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(dateFmt.format(Date(e.first.createdAt)), style = MaterialTheme.typography.bodySmall)
                        if (e.count > 1) Text("${e.count} finds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            if (spots.isNotEmpty()) {
                HorizontalDivider()
                Text("Hotspots", style = MaterialTheme.typography.titleMedium)
                Text("Places (about 1 km across) where you've found the most species. The Map tab can show these too.",
                    style = MaterialTheme.typography.bodySmall)
                spots.forEach { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${h.species} species · ${h.finds} finds", style = MaterialTheme.typography.titleSmall)
                                Text(h.topNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${h.lat},${h.lng}?z=15")))
                            }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
