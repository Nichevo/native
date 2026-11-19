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
import androidx.compose.runtime.setValue
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
    /** Birds you agree were in the recording (lower-case scientific names). Each becomes its own find. */
    val chosen: Set<String> = emptySet(),
    val notes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val startedAt: Long? = null,
    val saving: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val method: String? = null,
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
                _state.update { it.copy(
                    analysing = false, candidates = r.candidates, messages = r.messages, method = r.method,
                    // Tick the clear ones (50%+), or the top one if none is that clear.
                    chosen = (r.candidates.filter { c -> c.score >= 0.5f }.ifEmpty { r.candidates.take(1) })
                        .map { c -> c.scientificName.lowercase() }.toSet(),
                ) }
            }.onFailure { e ->
                _state.update { it.copy(analysing = false, messages = listOf("Couldn't analyse the recording: ${e.message}")) }
            }
        }
    }

    fun toggle(cand: Candidate) = _state.update {
        val k = cand.scientificName.lowercase()
        it.copy(chosen = if (k in it.chosen) it.chosen - k else it.chosen + k)
    }
    fun setNotes(n: String) = _state.update { it.copy(notes = n) }

    /**
     * Saves each ticked bird as its own find, each with its own copy of the recording and the full list
     * of birds heard in its ID history. With none ticked, saves the recording as one unidentified find.
     */
    fun save(onSaved: (List<Long>) -> Unit) {
        val st = _state.value
        val pcm = st.pcm ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val chosen = st.candidates.filter { it.scientificName.lowercase() in st.chosen }
            val targets: List<Candidate?> = chosen.ifEmpty { listOf(null) }
            val secs = pcm.size / BirdSongClassifier.SAMPLE_RATE
            val others = chosen.map { it.commonName ?: it.scientificName }
            val ids = mutableListOf<Long>()
            for ((n, sel) in targets.withIndex()) {
                val file = c.photos.newSoundFile()
                SoundRecorder.writeWav(pcm, BirdSongClassifier.SAMPLE_RATE, file)
                val info = sel?.let { runCatching { c.species.info(it.scientificName, st.settings.regionPlaceId, st.settings.offlineMode) }.getOrNull() }
                val shared = if (chosen.size > 1) "One of ${chosen.size} birds in the same recording: ${others.joinToString()}." else null
                val find = Find(
                    photoPath = file.path,
                    createdAt = (st.startedAt ?: System.currentTimeMillis()) + n, // keeps them in order
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
                    notes = listOfNotNull(st.notes.takeIf { it.isNotBlank() }, shared, sel?.note).joinToString("\n\n"),
                )
                val id = c.db.finds().insert(find)
                c.db.finds().insertPhotos(listOf(FindPhoto(findId = id, path = file.path, category = SOUND, addedAt = System.currentTimeMillis())))
                val now = System.currentTimeMillis()
                c.db.idRuns().insert(buildList {
                    add(com.nativefinds.app.id.IdHistory.run(id, now, com.nativefinds.app.id.IdHistory.SONG,
                        "Recorded $secs s of song", st.method, st.candidates))
                    if (sel != null && (chosen.size > 1 || sel != st.candidates.firstOrNull())) add(com.nativefinds.app.id.IdHistory.run(
                        id, now + 1, com.nativefinds.app.id.IdHistory.CHOICE,
                        "You ticked " + (sel.commonName ?: sel.scientificName),
                        (if (chosen.size > 1) "You ticked ${chosen.size} birds in this recording; each is its own find. " else "") +
                            "${(sel.score * 100).toInt()}% from ${sel.source.label}.",
                        emptyList(), chosen = sel.scientificName))
                })
                ids += id
            }
            _state.value = ListenState(settings = st.settings, includeElsewhere = st.includeElsewhere)
            onSaved(ids)
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
fun ListenScreen(container: AppContainer, onBack: () -> Unit, onSaved: (List<Long>) -> Unit, onChorus: () -> Unit = {}, autoStart: Boolean = false) {
    val vm: ListenViewModel = viewModel { ListenViewModel(container) }
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.start()
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    val available = remember { container.birdSong.isAvailable() }
    // Opened from the main page's Listen button: start recording straight away.
    var autoStarted by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (autoStart && !autoStarted && available) {
            autoStarted = true
            val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasMic) vm.start() else askPermissions.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

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
                Text(
                    if (st.candidates.size == 1) "Bird heard. Tap to untick it if you don't agree."
                    else "${st.candidates.size} birds heard. Tick each one you agree with; each is saved as its own find.",
                    style = MaterialTheme.typography.titleSmall,
                )
                st.candidates.forEach { cand ->
                    val on = cand.scientificName.lowercase() in st.chosen
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { vm.toggle(cand) })
                        CandidateRow(
                            cand,
                            st.settings.regionName,
                            onClick = { vm.toggle(cand) },
                            selected = on,
                            pest = container.pests.lookup(cand.scientificName, st.settings.regionPlaceId),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                st.candidates.filter { it.scientificName.lowercase() in st.chosen }.forEach { sel ->
                    container.pests.lookup(sel.scientificName, st.settings.regionPlaceId)?.let { PestBanner(it) }
                }
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
                        Text(
                            when (st.chosen.size) {
                                0 -> "Save recording"
                                1 -> "Save find"
                                else -> "Save ${st.chosen.size} finds"
                            },
                        )
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
