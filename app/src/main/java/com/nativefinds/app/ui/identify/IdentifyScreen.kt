package com.nativefinds.app.ui.identify

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.CandidateRow
import com.nativefinds.app.ui.common.PestBanner
import com.nativefinds.app.ui.common.SpeciesInfoCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyScreen(container: AppContainer, editFindId: Long? = null, start: String? = null, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val vm: IdentifyViewModel = viewModel(key = "identify-${editFindId ?: "new"}") { IdentifyViewModel(container, editFindId) }
    val st by vm.state.collectAsStateWithLifecycle()

    val leave = { vm.discard(); onBack() }
    BackHandler(onBack = leave)

    // Opened straight from the main page's Camera or Gallery button: if you back out without a photo, go back.
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCaptured(ok)
        if (!ok && start != null && !vm.state.value.hasPhotos) leave()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        vm.onPicked(uri)
        if (uri == null && start != null && !vm.state.value.hasPhotos) leave()
    }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!launched && editFindId == null && !vm.state.value.hasPhotos) {
            launched = true
            when (start) {
                "camera" -> takePicture.launch(vm.newCaptureUri())
                "gallery" -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it } && editFindId == null) vm.locate()
    }
    LaunchedEffect(Unit) {
        if (!container.location.hasPermission()) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val canAskPc = st.settings.pcAiConfigured && !st.settings.offlineMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editFindId == null) "New find" else "Edit find") },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!st.hasPhotos) {
                Text(
                    "Take a photo or choose one from your gallery. You'll say what it shows afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (st.photos.size > 1) {
                Text("Next photo shows:", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { cat ->
                        FilterChip(selected = st.category == cat, onClick = { vm.setCategory(cat) }, label = { Text(cat.label) })
                    }
                }
            }
            if (st.settings.offlineMode) {
                Text("Offline mode: using on-phone models only.", color = MaterialTheme.colorScheme.tertiary)
            }

            if (st.hasPhotos) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.photos.forEach { p ->
                        Box {
                            AsyncImage(
                                model = File(p.path),
                                contentDescription = p.category.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(if (st.photos.size == 1) 260.dp else 150.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Text(
                                p.category.label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.BottomStart)
                                    .background(Color(0x99000000), RoundedCornerShape(topEnd = 8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            IconButton(onClick = { vm.removePhoto(p.path) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                Icon(Icons.Filled.Close, "Remove photo", tint = Color.White,
                                    modifier = Modifier.background(Color(0x99000000), CircleShape))
                            }
                        }
                    }
                }
                if (st.photos.size < 5) {
                    Text(
                        if (st.mainCategory.isPlant) "Tip: add a leaf, a flower and bark of the same plant for a stronger match."
                        else "Tip: another angle of the same animal can firm up the match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { takePicture.launch(vm.newCaptureUri()) }, enabled = st.photos.size < 5) {
                    Icon(Icons.Filled.CameraAlt, null); Text(if (st.hasPhotos) "  Add photo" else "  Take photo")
                }
                OutlinedButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = st.photos.size < 5) {
                    Icon(Icons.Filled.Image, null); Text("  From gallery")
                }
            }

            if (st.photos.size == 1) {
                Text("What did you photograph?", style = MaterialTheme.typography.titleMedium)
                when {
                    st.guessing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp)); Text("Working out what this is…", style = MaterialTheme.typography.bodySmall)
                    }
                    st.needsType -> Text("Tap what it shows to start identifying.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    st.guess != null && st.guess == st.photos[0].category -> Card(Modifier.fillMaxWidth()) {
                        Text(
                            "✨ Looks like ${if (st.guess == Category.LEAF) "a plant (leaf)" else "a " + st.guess?.label?.lowercase()}, so I've started with that. Tap another if it's wrong.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                TypeGrid(selected = if (st.needsType || st.guessing) null else st.photos[0].category) { vm.setCategory(it) }
            }
            if (st.hasPhotos) {
                LocationLine(st, onRetry = { vm.locate() })
            }

            if (st.identifying) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Text("Identifying from ${st.photos.size} photo${if (st.photos.size == 1) "" else "s"}…")
                }
            }
            st.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            if (st.candidates.isNotEmpty()) {
                Text(
                    "Possible matches from ${st.photos.size} photo${if (st.photos.size == 1) "" else "s"}. Tap one to choose it.",
                    style = MaterialTheme.typography.titleSmall,
                )
                st.candidates.forEach { cand ->
                    CandidateRow(
                        cand,
                        st.settings.regionName,
                        onClick = { vm.select(cand) },
                        selected = st.selected?.scientificName == cand.scientificName,
                        previousScore = st.previousScores[cand.scientificName.lowercase()],
                        pest = container.pests.lookup(cand.scientificName, st.settings.regionPlaceId),
                    )
                }
            }
            if (!st.identifying && st.candidates.isNotEmpty() && st.photos.size < 5) {
                val used = st.photos.map { it.category }.toSet()
                val suggestions = if (st.mainCategory.isPlant) {
                    listOf(Category.LEAF, Category.FLOWER, Category.FRUIT, Category.BARK, Category.WHOLE_PLANT).filterNot { it in used }
                } else {
                    listOf(st.mainCategory)
                }
                val best = st.candidates.maxOf { it.score }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (best < 0.7f) "Not sure yet (best ${(best * 100).toInt()}%). Add another photo to confirm."
                            else "Add another photo to make it more certain.",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (st.mainCategory.isPlant) "Photos of different parts of the same plant are combined, so the % goes up when they agree."
                            else "Another photo of the same animal from a different angle is combined with the first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { cat ->
                                OutlinedButton(onClick = {
                                    vm.setNextCategory(cat)
                                    takePicture.launch(vm.newCaptureUri())
                                }) {
                                    Icon(Icons.Filled.CameraAlt, null)
                                    Text("  " + if (st.mainCategory.isPlant) cat.label else "Another photo")
                                }
                            }
                        }
                    }
                }
            }
            st.pcNotes?.let { Text("PC AI notes: $it", style = MaterialTheme.typography.bodyMedium) }

            if (st.runs.isNotEmpty()) {
                var showRuns by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.material3.TextButton(onClick = { showRuns = !showRuns }) {
                    Text(if (showRuns) "Hide how the % changed" else "How the % changed with each photo (${st.runs.size} steps)")
                }
                if (showRuns) {
                    com.nativefinds.app.ui.common.IdHistoryView(st.runs, st.selected?.scientificName, onUse = null)
                    Text("This history is saved with the find, so you can look at it again later.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            if (st.sounds.isNotEmpty() && !st.hasPhotos) {
                Text(
                    "This find is a sound recording. You can change the species by hand, or add a photo to identify it from that.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (st.hasMedia && !st.identifying) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (st.hasPhotos && editFindId != null && st.candidates.isEmpty()) {
                        OutlinedButton(onClick = { vm.identify() }) { Text("Re-identify") }
                    }
                    if (canAskPc && st.hasPhotos) {
                        OutlinedButton(onClick = { vm.askPc() }, enabled = !st.askingPc) {
                            Icon(Icons.Filled.Computer, null); Text(if (st.askingPc) "  Asking your PC…" else "  Ask my PC")
                        }
                    }
                }
                ManualEntry(onEnter = vm::enterManually)
            }

            st.selected?.let { sel ->
                container.pests.lookup(sel.scientificName, st.settings.regionPlaceId)?.let { PestBanner(it) }
                Text("About this species", style = MaterialTheme.typography.titleSmall)
                SpeciesInfoCard(st.selectedInfo, sel.commonName ?: sel.scientificName, st.settings.regionName, st.loadingInfo)
            }

            if (st.hasMedia) {
                OutlinedTextField(
                    value = st.notes,
                    onValueChange = vm::setNotes,
                    label = { Text("Notes (habitat, behaviour, size…)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                if (st.pcUnreachable) {
                    OutlinedButton(onClick = { vm.save(askPcLater = true, onSaved = onSaved) }, enabled = !st.saving, modifier = Modifier.fillMaxWidth()) {
                        Text("Save and ask my PC later")
                    }
                }
                Button(onClick = { vm.save(onSaved = onSaved) }, enabled = !st.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            editFindId != null -> "Save changes"
                            st.selected == null -> "Save as unidentified"
                            else -> "Save find"
                        },
                    )
                }
                if (canAskPc && !st.pcUnreachable && editFindId == null) {
                    TextButton(onClick = { vm.save(askPcLater = true, onSaved = onSaved) }, enabled = !st.saving) {
                        Text("Save now, let my PC check it in the background")
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LocationLine(st: IdentifyState, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp))
        val text = when {
            st.locating -> "  Getting location…"
            st.latitude != null && st.longitude != null ->
                "  %.5f, %.5f".format(st.latitude, st.longitude) + (st.accuracy?.let { " (±${it.toInt()} m)" } ?: "")
            else -> "  No location"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!st.locating) TextButton(onClick = onRetry) { Text("Refresh") }
    }
}

@Composable
private fun ManualEntry(onEnter: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("I know what it is") }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Scientific name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onEnter(text); open = false }) { Text("Use") }
        }
    }
}

private val TYPE_EMOJI = mapOf(
    Category.BIRD to "🐦", Category.LEAF to "🍃", Category.FLOWER to "🌸", Category.FRUIT to "🍒", Category.BARK to "🪵",
    Category.WHOLE_PLANT to "🌳", Category.INSECT to "🐞", Category.LIZARD to "🦎", Category.OTHER to "❓",
)

/** Big tappable tiles for what the photo shows. */
@Composable
private fun TypeGrid(selected: Category?, onPick: (Category) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Category.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    val on = cat == selected
                    Card(
                        onClick = { onPick(cat) },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        border = if (on) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(TYPE_EMOJI[cat] ?: "", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                cat.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
