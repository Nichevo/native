package com.nativefinds.app.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Regions
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.id.Category
import com.nativefinds.app.id.PcQueueWorker
import com.nativefinds.app.ui.common.PestBanner
import com.nativefinds.app.ui.common.SoundPlayer
import com.nativefinds.app.audio.isAudioPath
import com.nativefinds.app.ui.common.SpeciesInfoCard
import com.nativefinds.app.ui.common.formatDate
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindDetailScreen(
    container: AppContainer,
    findId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val find by remember(findId) { container.db.finds().observe(findId) }.collectAsState(initial = null)
    val photos by remember(findId) { container.db.finds().observePhotos(findId) }.collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<SpeciesInfo?>(null) }
    var loadingInfo by remember { mutableStateOf(false) }
    var notes by remember(findId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val f = find
    val placeId = f?.regionPlaceId ?: settings?.regionPlaceId ?: Regions.NEW_ZEALAND.placeId
    val regionName = Regions.nameFor(placeId)

    LaunchedEffect(f?.scientificName, settings?.offlineMode) {
        val name = f?.scientificName ?: return@LaunchedEffect
        val s = settings ?: return@LaunchedEffect
        loadingInfo = true
        info = container.species.info(name, placeId, s.offlineMode)
        loadingInfo = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f?.commonName ?: f?.scientificName ?: "Find") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { onEdit(findId) }) { Icon(Icons.Filled.Edit, "Edit, add photos or re-identify") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        if (f == null) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val all = photos.map { it.path }.ifEmpty { listOf(f.photoPath) }
            val paths = all.filterNot(::isAudioPath)
            val sounds = all.filter(::isAudioPath)
            if (paths.isEmpty()) {
                // Sound-only find
            } else if (paths.size == 1) {
                AsyncImage(
                    model = File(paths[0]),
                    contentDescription = "Your photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.filterNot { isAudioPath(it.path) }.forEach { p ->
                        Column {
                            AsyncImage(
                                model = File(p.path),
                                contentDescription = p.category,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Text(
                                runCatching { Category.valueOf(p.category).label }.getOrDefault(p.category),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            sounds.forEach { SoundPlayer(it) }
            if (f.pendingPc) {
                Text("Waiting for your PC to check this find. It'll update when your PC is reachable.",
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
            val cat = runCatching { Category.valueOf(f.category).label }.getOrDefault(f.category)
            Text("$cat · ${formatDate(f.createdAt)}", style = MaterialTheme.typography.bodyMedium)
            f.idSource?.let { src ->
                val conf = f.confidence?.let { " (${(it * 100).toInt()}%)" } ?: ""
                Text("Identified by $src$conf", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (f.latitude != null && f.longitude != null) {
                Row {
                    Text(
                        "%.5f, %.5f".format(f.latitude, f.longitude) + (f.accuracyMeters?.let { " (±${it.toInt()} m)" } ?: ""),
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                    )
                    TextButton(onClick = {
                        val label = Uri.encode(f.commonName ?: f.scientificName ?: "Find")
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${f.latitude},${f.longitude}($label)")))
                    }) { Text("Open in maps") }
                }
            } else {
                Text("No location saved", color = MaterialTheme.colorScheme.outline)
            }

            OutlinedButton(onClick = { onEdit(findId) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Edit, null)
                Text(if (f.scientificName == null) "  Review: identify this find" else "  Review: add photos, re-identify or change species")
            }
            container.pests.lookup(f.scientificName, placeId)?.let { PestBanner(it) }
            if (f.scientificName != null) {
                SpeciesInfoCard(info, f.commonName ?: f.scientificName, regionName, loadingInfo)
                Button(onClick = { onOpenSpecies(f.scientificName) }, modifier = Modifier.fillMaxWidth()) {
                    Text("More about this species, and all your finds of it")
                }
            }

            val current = notes ?: f.notes
            OutlinedTextField(
                value = current,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            if (notes != null && notes != f.notes) {
                Button(onClick = {
                    scope.launch { container.db.finds().update(f.copy(notes = current)); notes = null }
                }) { Text("Save notes") }
            }
            val s = settings
            if (s != null && s.pcAiConfigured && !s.offlineMode && !f.pendingPc && !isAudioPath(f.photoPath)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        container.db.finds().update(f.copy(pendingPc = true))
                        PcQueueWorker.runNow(ctx)
                    }
                }) { Text("Ask my PC about this find") }
            }
            OutlinedButton(onClick = {
                val text = buildString {
                    append(f.commonName ?: f.scientificName ?: "Unidentified find")
                    f.scientificName?.let { append(" ($it)") }
                    append("\n").append(formatDate(f.createdAt))
                    if (f.latitude != null) append("\nhttps://www.openstreetmap.org/?mlat=${f.latitude}&mlon=${f.longitude}#map=16/${f.latitude}/${f.longitude}")
                    if (f.notes.isNotBlank()) append("\n\n").append(f.notes)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(f.photoPath))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (isAudioPath(f.photoPath)) "audio/wav" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Share find"))
            }) { Text("Share") }
        }
    }

    if (confirmDelete && f != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this find?") },
            text = { Text("The photo and details will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val all = container.db.finds().photos(f.id).map { it.path } + f.photoPath
                        container.db.finds().delete(f)
                        all.distinct().forEach { container.photos.delete(it) }
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
