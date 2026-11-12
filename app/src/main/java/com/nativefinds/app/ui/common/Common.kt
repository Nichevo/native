package com.nativefinds.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.RegionMatch
import java.text.DateFormat
import java.util.Date

fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Human wording + colour for iNaturalist establishment means. */
fun establishmentLabel(e: String?): Pair<String, Color>? = when (e?.lowercase()) {
    null, "" -> null
    "endemic" -> "Endemic" to Color(0xFF1B7F3B)
    "native" -> "Native" to Color(0xFF2E7D32)
    "introduced", "naturalised", "naturalized" -> "Introduced" to Color(0xFFB26A00)
    "invasive" -> "Invasive" to Color(0xFFC62828)
    "managed" -> "Cultivated" to Color(0xFF6D4C41)
    else -> e.replaceFirstChar { it.uppercase() } to Color(0xFF546E7A)
}

@Composable
fun StatusChip(text: String, color: Color) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
    )
}

@Composable
fun CandidateRow(
    c: Candidate,
    regionName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.photoUrl != null) {
                    AsyncImage(
                        model = c.photoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.commonName ?: c.scientificName, style = MaterialTheme.typography.titleMedium)
                    c.maoriName?.takeIf { !it.equals(c.commonName, ignoreCase = true) }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (c.commonName != null) {
                        Text(c.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("${(c.score * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                when (c.regionMatch) {
                    RegionMatch.RECORDED -> Text("· recorded in $regionName", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                    RegionMatch.NOT_RECORDED -> Text("· not recorded in $regionName", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB26A00))
                    RegionMatch.UNKNOWN -> {}
                }
                establishmentLabel(c.establishment)?.let { (t, col) ->
                    Text("· $t", style = MaterialTheme.typography.labelSmall, color = col)
                }
            }
            c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
fun SpeciesInfoCard(info: SpeciesInfo?, fallbackName: String, regionName: String, loading: Boolean) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info?.photoUrl != null) {
                    AsyncImage(
                        model = info.photoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(info?.commonName ?: fallbackName, style = MaterialTheme.typography.titleLarge)
                    info?.maoriName?.takeIf { !it.equals(info.commonName, ignoreCase = true) }?.let {
                        Text("Te reo Māori: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(info?.scientificName ?: fallbackName, fontStyle = FontStyle.Italic)
                    info?.family?.let { Text("Family: $it", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        establishmentLabel(info?.establishment)?.let { (t, c) -> StatusChip("$t to $regionName", c) }
                    }
                }
            }
            info?.conservationStatus?.let {
                Text("Conservation status: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            when {
                loading -> Text("Loading species info…", modifier = Modifier.padding(top = 8.dp))
                info?.summary != null -> Text(info.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                info == null -> Text("No species info available (offline, or not found).", modifier = Modifier.padding(top = 8.dp))
            }
            Row {
                info?.wikipediaUrl?.let { url ->
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Wikipedia") }
                }
                info?.taxonId?.let { id ->
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.inaturalist.org/taxa/$id")))
                    }) { Text("iNaturalist") }
                }
            }
        }
    }
}
