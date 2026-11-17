package com.nativefinds.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nativefinds.app.data.IdRun
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.IdHistory
import com.nativefinds.app.id.RegionMatch

private fun key(name: String) = name.trim().lowercase().split(Regex("\\s+")).take(2).joinToString(" ")

/**
 * The story of how a find was identified: each run's matches and %, how the % moved since the run
 * before, each photo's own matches, and how the numbers were worked out. [onUse] switches the find
 * to another species from the latest matches.
 */
@Composable
fun IdHistoryView(runs: List<IdRun>, currentName: String?, onUse: ((Candidate) -> Unit)?) {
    val scored = runs.filter { it.kind != IdHistory.CHOICE }
    val latestScored = scored.lastOrNull()
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll || runs.size <= 3) runs else runs.takeLast(3)
    if (runs.size > 3) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Show only the latest" else "Show all ${runs.size} steps")
        }
    }
    shown.forEach { run ->
        val idx = runs.indexOf(run)
        val previous = runs.take(idx).lastOrNull { it.kind != IdHistory.CHOICE }
        RunCard(
            run = run,
            previous = previous,
            currentName = currentName,
            onUse = if (run == latestScored) onUse else null,
        )
    }
}

@Composable
private fun RunCard(run: IdRun, previous: IdRun?, currentName: String?, onUse: ((Candidate) -> Unit)?) {
    var showPhotos by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf(false) }
    val cands = remember(run) { IdHistory.candidates(run) }
    val perPhoto = remember(run) { IdHistory.perPhoto(run) }
    val before = remember(previous) { previous?.let { IdHistory.candidates(it).associateBy { c -> key(c.scientificName) } } }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (run.kind == IdHistory.CHOICE) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(run.label, style = MaterialTheme.typography.titleSmall)
            Text(
                formatDate(run.at) + when (run.kind) {
                    IdHistory.PC -> " · your PC"
                    IdHistory.SONG -> " · bird song"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (run.kind == IdHistory.CHOICE) {
                run.method?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                return@Column
            }
            if (cands.isEmpty()) Text("No matches.", style = MaterialTheme.typography.bodySmall)
            cands.forEach { c ->
                val isCurrent = currentName != null && key(c.scientificName) == key(currentName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (isCurrent) "✓ " else "") + (c.commonName ?: c.scientificName),
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (c.commonName != null) Text(c.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                        if (c.regionMatch == RegionMatch.NOT_RECORDED) {
                            Text("Not recorded in your region", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        c.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${pct(c.score)}%", style = MaterialTheme.typography.titleSmall)
                        before?.let { b -> Delta(b[key(c.scientificName)]?.score, c.score) }
                    }
                }
                LinearProgressIndicator(progress = { c.score.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (onUse != null && !isCurrent) {
                    TextButton(onClick = { onUse(c) }, modifier = Modifier.padding(0.dp)) { Text("Use this one instead") }
                }
            }
            // Species that dropped out since the run before.
            before?.let { b ->
                val gone = b.keys - cands.map { key(it.scientificName) }.toSet()
                if (gone.isNotEmpty()) {
                    Text(
                        "No longer in the top matches: " + gone.mapNotNull { b[it] }.joinToString { c ->
                            "${c.commonName ?: c.scientificName} (was ${pct(c.score)}%)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row {
                if (perPhoto.isNotEmpty()) {
                    TextButton(onClick = { showPhotos = !showPhotos }) { Text(if (showPhotos) "Hide each photo" else "Each photo's matches") }
                }
                if (run.method != null) {
                    TextButton(onClick = { showHow = !showHow }) { Text(if (showHow) "Hide" else "How the % works") }
                }
            }
            if (showPhotos) {
                perPhoto.forEach { p ->
                    Text(p.photo, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (p.candidates.isEmpty()) "No match" else p.candidates.take(3).joinToString(" · ") { "${it.commonName ?: it.scientificName} ${pct(it.score)}%" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Show the sum for the top species so the combined number can be checked by hand.
                cands.firstOrNull()?.let { top ->
                    val parts = perPhoto.map { p -> p.candidates.firstOrNull { key(it.scientificName) == key(top.scientificName) }?.score ?: 0f }
                    if (parts.count { it > 0f } > 1) {
                        val combined = 1.0 - parts.fold(1.0) { acc, s -> acc * (1.0 - s.coerceIn(0f, 0.999f)) }
                        Text(
                            "${top.commonName ?: top.scientificName}: 1 − " + parts.joinToString(" × ") { "(1 − ${pct(it)}%)" } +
                                " = ${pct(combined.toFloat())}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (showHow) run.method?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Delta(before: Float?, now: Float) {
    val (text, color) = when {
        before == null -> "new" to MaterialTheme.colorScheme.primary
        pct(now) - pct(before) >= 1 -> "▲ ${pct(now) - pct(before)}" to Color(0xFF2E7D32)
        pct(before) - pct(now) >= 1 -> "▼ ${pct(before) - pct(now)}" to MaterialTheme.colorScheme.error
        else -> "same" to MaterialTheme.colorScheme.outline
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

private fun pct(f: Float): Int = (f * 100).toInt()
