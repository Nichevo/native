package com.nativefinds.app.ui.listen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nativefinds.app.AppContainer
import com.nativefinds.app.audio.BirdSongClassifier
import com.nativefinds.app.audio.SoundRecorder
import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.FindPhoto
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.CandidateRow
import com.nativefinds.app.ui.common.PestBanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_SECONDS = 30

data class ListenState(
    val recording: Boolean = false,
    val seconds: Float = 0f,
    val level: Float = 0f,
    val analysing: Boolean = false,
    val pcm: ShortArray? = null,
    val candidates: List<Candidate> = emptyList(),
    val messages: List<String> = emptyList(),
    val includeElsewhere: Boolean = false,
    val selected: Candidate? = null,
    val notes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val startedAt: Long? = null,
    val saving: Boolean = false,
    val settings: AppSettings = AppSettings(),
)

class ListenViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state
    @Volatile private var stopRequested = false
    private var job: Job? = null

    init {
        viewModelScope.launch { c.settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
    }

    fun start() {
        if (_state.value.recording) return
        stopRequested = false
        _state.update {
            ListenState(recording = true, includeElsewhere = it.includeElsewhere, settings = it.settings, startedAt = System.currentTimeMillis())
        }
        viewModelScope.launch {
            c.location.current()?.let { loc ->
                _state.update { it.copy(latitude = loc.latitude, longitude = loc.longitude, accuracy = loc.accuracy) }
            }
        }
        job = viewModelScope.launch {
            val pcm = runCatching {
                SoundRecorder.record(BirdSongClassifier.SAMPLE_RATE, MAX_SECONDS, { stopRequested }) { s, l ->
                    _state.update { it.copy(seconds = s, level = l) }
                }
            }.getOrElse { e ->
                _state.update { it.copy(recording = false, messages = listOf("Recording failed: ${e.message}")) }
                return@launch
            }
            _state.update { it.copy(recording = false, pcm = pcm) }
            analyse()
        }
    }

    fun stop() { stopRequested = true }

    fun setIncludeElsewhere(v: Boolean) {
        _state.update { it.copy(includeElsewhere = v) }
        if (_state.value.pcm != null) analyse()
    }

    private fun analyse() {
        val pcm = _state.value.pcm ?: return
        if (pcm.size < BirdSongClassifier.SAMPLE_RATE) {
            _state.update { it.copy(messages = listOf("That was very short. Record for at least 3 seconds.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(analysing = true, messages = emptyList()) }
            val st = _state.value
            val result = runCatching { c.identifier.identifySong(SoundRecorder.toFloats(pcm), st.settings, st.includeElsewhere) }
            result.onSuccess { r ->
                _state.update { it.copy(analysing = false, candidates = r.candidates, messages = r.messages, selected = r.candidates.firstOrNull()) }
            }.onFailure { e ->
                _state.update { it.copy(analysing = false, messages = listOf("Couldn't analyse the recording: ${e.message}")) }
            }
        }
    }

    fun select(cand: Candidate) = _state.update { it.copy(selected = cand) }
    fun setNotes(n: String) = _state.update { it.copy(notes = n) }

    /** Saves the recording as its own find (its own date and place), keeping the sound file. */
    fun save(onSaved: (Long) -> Unit) {
        val st = _state.value
        val pcm = st.pcm ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val file = c.photos.newSoundFile()
            SoundRecorder.writeWav(pcm, BirdSongClassifier.SAMPLE_RATE, file)
            val sel = st.selected
            val info = sel?.let { runCatching { c.species.info(it.scientificName, st.settings.regionPlaceId, st.settings.offlineMode) }.getOrNull() }
            val find = Find(
                photoPath = file.path,
                createdAt = st.startedAt ?: System.currentTimeMillis(),
                latitude = st.latitude,
                longitude = st.longitude,
                accuracyMeters = st.accuracy,
                category = Category.BIRD.name,
                scientificName = sel?.scientificName,
                commonName = info?.commonName ?: sel?.commonName,
                confidence = sel?.score,
                idSource = sel?.source?.label,
                taxonId = info?.taxonId,
                establishment = info?.establishment ?: sel?.establishment,
                regionPlaceId = st.settings.regionPlaceId,
                notes = st.notes,
            )
            val id = c.db.finds().insert(find)
            c.db.finds().insertPhotos(listOf(FindPhoto(findId = id, path = file.path, category = SOUND, addedAt = System.currentTimeMillis())))
            _state.value = ListenState(settings = st.settings, includeElsewhere = st.includeElsewhere)
            onSaved(id)
        }
    }

    override fun onCleared() { stopRequested = true }

    companion object {
        /** FindPhoto.category for a sound recording. */
        const val SOUND = "SOUND"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(container: AppContainer, onBack: () -> Unit, onSaved: (Long) -> Unit, onChorus: () -> Unit = {}) {
    val vm: ListenViewModel = viewModel { ListenViewModel(container) }
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.start()
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    val available = remember { container.birdSong.isAvailable() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identify by song") },
                navigationIcon = { IconButton(onClick = { vm.stop(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!available) {
                Text("The bird-song model isn't in this build.", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text(
                "Point your phone at the bird and record while it calls (up to $MAX_SECONDS seconds). BirdNET listens on the phone, " +
                    "so this works offline too.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!st.recording && st.pcm == null) {
                OutlinedButton(onClick = onChorus) { Text("Dawn chorus: listen for longer and list every bird") }
            }
            if (st.recording) {
                Text("Listening… ${st.seconds.toInt()} s", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { st.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                LinearProgressIndicator(progress = { st.seconds / MAX_SECONDS }, modifier = Modifier.fillMaxWidth())
                Button(onClick = vm::stop) { Icon(Icons.Filled.Stop, null); Text("  Stop and identify") }
            } else {
                Button(
                    enabled = !st.analysing && !st.saving,
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) vm.start() else askPermissions.launch(
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                ) { Icon(Icons.Filled.Mic, null); Text(if (st.pcm == null) "  Start listening" else "  Record again") }
            }
            if (st.analysing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text("Identifying ${st.seconds.toInt()} s of sound…")
                }
            }
            FilterChip(
                selected = st.includeElsewhere,
                onClick = { vm.setIncludeElsewhere(!st.includeElsewhere) },
                label = { Text("Include birds not recorded in ${st.settings.regionName}") },
            )
            st.messages.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (st.candidates.isNotEmpty()) {
                Text("Birds heard. Tap one to choose it.", style = MaterialTheme.typography.titleSmall)
                st.candidates.forEach { cand ->
                    CandidateRow(
                        cand,
                        st.settings.regionName,
                        onClick = { vm.select(cand) },
                        selected = st.selected?.scientificName == cand.scientificName,
                        pest = container.pests.lookup(cand.scientificName, st.settings.regionPlaceId),
                    )
                }
                st.selected?.let { sel -> container.pests.lookup(sel.scientificName, st.settings.regionPlaceId)?.let { PestBanner(it) } }
            }
            if (st.pcm != null && !st.recording) {
                OutlinedTextField(
                    value = st.notes,
                    onValueChange = vm::setNotes,
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !st.saving && !st.analysing, onClick = { vm.save(onSaved) }) {
                        Text(if (st.selected == null) "Save recording" else "Save find")
                    }
                    OutlinedButton(onClick = { vm.stop(); onBack() }) { Text("Discard") }
                }
            }
            Text(
                "Bird-song ID by BirdNET (K. Lisa Yang Center for Conservation Bioacoustics, Cornell Lab of Ornithology, " +
                    "and Chemnitz University of Technology), CC BY-NC-SA 4.0.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
