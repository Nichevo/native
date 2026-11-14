package com.nativefinds.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.BuildConfig
import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.data.CsvExporter
import com.nativefinds.app.data.Region
import com.nativefinds.app.data.Regions
import com.nativefinds.app.id.OnDeviceClassifier
import com.nativefinds.app.ui.common.formatDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val saved by container.settings.settings.collectAsState(initial = null)
    val s = saved ?: return
    // Local editable copy so typing doesn't fight DataStore round-trips.
    var draft by remember(s.regionPlaceId, s.offlineMode) { mutableStateOf(s) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val packs by container.regions.packs.collectAsState(initial = emptyList())
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun persist(next: AppSettings) {
        draft = next
        scope.launch { container.settings.update { next } }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Region")
            RegionPicker(draft) { r -> persist(draft.copy(regionPlaceId = r.placeId, regionName = r.name)) }
            Text(
                "Native or introduced status, and which species are expected, are based on this region. " +
                    "Download a region pack before a trip so matches and species names work offline.",
                style = MaterialTheme.typography.bodySmall,
            )
            val pack = packs.firstOrNull { it.placeId == draft.regionPlaceId }
            Text(
                if (pack == null) "No pack downloaded for ${draft.regionName}"
                else "${pack.name} pack: ${pack.speciesCount} species, ${formatDate(pack.downloadedAt)}",
            )
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = progress == null && !draft.offlineMode, onClick = {
                    scope.launch {
                        progress = 0f
                        status = "Downloading ${draft.regionName} birds and plants…"
                        runCatching {
                            container.regions.download(draft.regionPlaceId, draft.regionName) { done, total ->
                                progress = if (total > 0) done.toFloat() / total else 0f
                            }
                        }.onSuccess { status = "Downloaded $it species" }
                            .onFailure { status = "Download failed: ${it.message}" }
                        progress = null
                    }
                }) { Text(if (pack == null) "Download pack" else "Update pack") }
                if (pack != null) {
                    OutlinedButton(onClick = { scope.launch { container.regions.delete(pack.placeId) } }) { Text("Remove") }
                }
            }
            if (packs.size > 1 || (packs.size == 1 && packs[0].placeId != draft.regionPlaceId)) {
                Text("Other packs on this phone: " + packs.filter { it.placeId != draft.regionPlaceId }.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Offline mode", style = MaterialTheme.typography.titleSmall)
                    Text("Use on-phone models only, with no internet calls. For places like China, or no signal.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = draft.offlineMode, onCheckedChange = { persist(draft.copy(offlineMode = it)) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save photos to gallery", style = MaterialTheme.typography.titleSmall)
                    Text("Photos taken in the app are also saved to your phone's gallery, in the Native Finds album.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = draft.saveToGallery, onCheckedChange = { persist(draft.copy(saveToGallery = it)) })
            }
            val birds = container.onDevice.isAvailable(OnDeviceClassifier.Model.BIRDS)
            val plants = container.onDevice.isAvailable(OnDeviceClassifier.Model.PLANTS)
            Text(
                "On-phone models: birds ${if (birds) "installed" else "missing"}, plants ${if (plants) "installed" else "missing"}",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Plant identification (Pl@ntNet)")
            OutlinedTextField(
                value = draft.plantNetApiKey,
                onValueChange = { draft = draft.copy(plantNetApiKey = it) },
                label = { Text("Pl@ntNet API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.plantnet.org/"))) }) {
                Text("Get a free key at my.plantnet.org")
            }

            HorizontalDivider()
            Section("Second opinion from your PC (Ollama)")
            OutlinedTextField(
                value = draft.pcAiUrl,
                onValueChange = { draft = draft.copy(pcAiUrl = it) },
                label = { Text("Address, e.g. https://ai.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.pcAiModel,
                onValueChange = { draft = draft.copy(pcAiModel = it) },
                label = { Text("Vision model name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.cfClientId,
                onValueChange = { draft = draft.copy(cfClientId = it) },
                label = { Text("Cloudflare Access client ID (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.cfClientSecret,
                onValueChange = { draft = draft.copy(cfClientSecret = it) },
                label = { Text("Cloudflare Access client secret (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { persist(draft); status = "Saved" }) { Text("Save") }
                OutlinedButton(onClick = {
                    persist(draft)
                    status = "Contacting your PC…"
                    scope.launch {
                        status = runCatching { container.ollama.listModels(draft) }.fold(
                            onSuccess = { models ->
                                if (models.isEmpty()) "Connected, but no models are installed"
                                else if (models.any { it == draft.pcAiModel || it.substringBefore(':') == draft.pcAiModel })
                                    "Connected. ${draft.pcAiModel} is ready."
                                else "Connected, but ${draft.pcAiModel} isn't installed. Available: ${models.joinToString()}"
                            },
                            onFailure = { "Couldn't reach your PC: ${it.message}" },
                        )
                    }
                }) { Text("Test connection") }
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            HorizontalDivider()
            Section("Your data")
            OutlinedButton(onClick = {
                scope.launch { ctx.startActivity(CsvExporter.shareIntent(ctx, container.db)) }
            }) { Text("Export finds to CSV") }
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionPicker(s: AppSettings, onPick: (Region) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = s.regionName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Region") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Regions.presets.forEach { r ->
                DropdownMenuItem(text = { Text(r.name) }, onClick = { onPick(r); expanded = false })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.filter(Char::isDigit) },
            label = { Text("Or an iNaturalist place ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            custom.toIntOrNull()?.let { onPick(Region(it, Regions.nameFor(it))); custom = "" }
        }) { Text("Use") }
    }
}
