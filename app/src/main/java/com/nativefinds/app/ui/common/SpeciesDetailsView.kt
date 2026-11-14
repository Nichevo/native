package com.nativefinds.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.data.Regions
import com.nativefinds.app.info.SpeciesDetails
import java.text.NumberFormat

/** The long part of a species page: photos, facts, article sections, look-alikes and links. */
@Composable
fun SpeciesDetailsView(
    details: SpeciesDetails?,
    loading: Boolean,
    scientificName: String,
    commonName: String?,
    isBird: Boolean,
    placeId: Int,
    regionName: String,
    onOpenSpecies: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val nf = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading && details == null) Text("Loading more about this species…", style = MaterialTheme.typography.bodySmall)
        if (details == null) {
            if (!loading) Text("More details need the internet the first time. After that they're saved for offline use.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            if (details.photos.size > 1) {
                Text("Photos", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(details.photos) { p ->
                        Column(Modifier.width(200.dp)) {
                            AsyncImage(
                                model = p.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            p.attribution?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }

            val facts = buildList {
                details.recordsInRegion?.let { add("Recorded ${nf.format(it)} times in $regionName on iNaturalist") }
                details.recordsWorldwide?.let { add("${nf.format(it)} records worldwide") }
            }
            if (facts.isNotEmpty() || details.taxonomy.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (details.taxonomy.isNotEmpty()) {
                            Text("Classification", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                            details.taxonomy.forEach { r ->
                                Row {
                                    Text(r.rank.replaceFirstChar { it.uppercase() }, Modifier.width(72.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Text(r.name + (r.common?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            if (details.sections.isNotEmpty()) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                details.sections.forEachIndexed { i, s -> ArticleSection(s, startOpen = i == 0) }
                Text("Text from Wikipedia (CC BY-SA).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            if (details.similar.isNotEmpty()) {
                Text("Often confused with", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(details.similar) { s ->
                        Column(Modifier.width(110.dp).clickable { onOpenSpecies(s.scientificName) }) {
                            AsyncImage(
                                model = s.photoUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Text(s.commonName ?: s.scientificName, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            if (s.commonName != null) {
                                Text(s.scientificName, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Links to the best local references.
        Row(Modifier.fillMaxWidth()) {
            val q = Uri.encode(commonName ?: scientificName)
            if (placeId == Regions.NEW_ZEALAND.placeId && isBird) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=site%3Anzbirdsonline.org.nz+$q")))
                }) { Text("NZ Birds Online") }
            }
            if (placeId == Regions.NEW_ZEALAND.placeId && !isBird) {
                TextButton(onClick = {
                    val slug = scientificName.trim().lowercase().split(Regex("\\s+")).take(2).joinToString("-")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nzpcn.org.nz/flora/species/$slug/")))
                }) { Text("NZ Plant Conservation Network") }
            }
        }
    }
}

@Composable
private fun ArticleSection(s: SpeciesDetails.Section, startOpen: Boolean) {
    var open by rememberSaveable(s.title) { mutableStateOf(startOpen) }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.clickable { open = !open }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand")
            }
            Text(
                s.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
