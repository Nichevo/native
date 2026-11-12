package com.nativefinds.app.ui.identify

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.CandidateRow
import com.nativefinds.app.ui.common.SpeciesInfoCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyScreen(container: AppContainer, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val vm: IdentifyViewModel = viewModel { IdentifyViewModel(container) }
    val st by vm.state.collectAsStateWithLifecycle()

    val leave = { vm.discard(); onBack() }
    BackHandler(onBack = leave)

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onCaptured(ok) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.onPicked(uri) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.locate()
    }
    LaunchedEffect(Unit) {
        if (!container.location.hasPermission()) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New find") },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("What are you photographing?", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { cat ->
                    FilterChip(selected = st.category == cat, onClick = { vm.setCategory(cat) }, label = { Text(cat.label) })
                }
            }
            if (st.settings.offlineMode) {
                Text("Offline mode: using on-phone models only.", color = MaterialTheme.colorScheme.tertiary)
            }

            if (st.photoPath != null) {
                AsyncImage(
                    model = File(st.photoPath!!),
                    contentDescription = "Your photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { takePicture.launch(vm.newCaptureUri()) }) {
                    Icon(Icons.Filled.CameraAlt, null); Text("  Take photo")
                }
                OutlinedButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Image, null); Text("  From gallery")
                }
            }

            if (st.photoPath != null) {
                LocationLine(st, onRetry = { vm.locate() })
            }

            if (st.identifying) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Text("Identifying…")
                }
            }
            st.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            if (st.candidates.isNotEmpty()) {
                Text("Possible matches. Tap one to choose it.", style = MaterialTheme.typography.titleSmall)
                st.candidates.forEach { cand ->
                    CandidateRow(
                        cand,
                        st.settings.regionName,
                        onClick = { vm.select(cand) },
                        selected = st.selected?.scientificName == cand.scientificName,
                    )
                }
            }
            st.pcNotes?.let { Text("PC AI notes: $it", style = MaterialTheme.typography.bodyMedium) }

            if (st.photoPath != null && !st.identifying) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (st.settings.pcAiConfigured && !st.settings.offlineMode) {
                        OutlinedButton(onClick = { vm.askPc() }, enabled = !st.askingPc) {
                            Icon(Icons.Filled.Computer, null); Text(if (st.askingPc) "  Asking your PC…" else "  Ask my PC")
                        }
                    }
                    ManualEntry(onEnter = vm::enterManually)
                }
            }

            st.selected?.let { sel ->
                Text("About this species", style = MaterialTheme.typography.titleSmall)
                SpeciesInfoCard(st.selectedInfo, sel.commonName ?: sel.scientificName, st.settings.regionName, st.loadingInfo)
            }

            if (st.photoPath != null) {
                OutlinedTextField(
                    value = st.notes,
                    onValueChange = vm::setNotes,
                    label = { Text("Notes (habitat, behaviour, size…)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Button(onClick = { vm.save(onSaved) }, enabled = !st.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (st.selected == null) "Save as unidentified" else "Save find")
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
