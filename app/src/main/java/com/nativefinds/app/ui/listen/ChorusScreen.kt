package com.nativefinds.app.ui.listen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nativefinds.app.AppContainer
import com.nativefinds.app.audio.BirdSongClassifier
import com.nativefinds.app.audio.SoundRecorder
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.FindPhoto
import com.nativefinds.app.id.Category
import com.nativefinds.app.id.IdSource
import com.nativefinds.app.id.RegionMatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val BLOCK_SECONDS = 30

/** A bird heard during a dawn-chorus session. */
data class Heard(
    val key: String,
    val scientificName: String,
    val commonName: String?,
    val bestScore: Float,
    val clips: Int,
    /** Seconds into the session when it was first heard. */
    val firstAt: Int,
    /** The 30 s clip where it was clearest. */
    val clip: File,
    val establishment: String?,
)

data class ChorusState(
    val recording: Boolean = false,
    val seconds: Float = 0f,
    val level: Float = 0f,
    val maxMinutes: Int = 10,
    val analysing: Int = 0,
    val heard: List<Heard> = emptyList(),
    val selected: Set<String> = emptySet(),
    val startedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

/**
 * Records for minutes at a time in 30-second clips. Each clip is identified while the next one
 * records, so a long dawn chorus never has to fit in memory at once.
 */
class ChorusViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ChorusState())
    val state: StateFlow<ChorusState> = _state
    @Volatile private var stopRequested = false
    private var job: Job? = null
    private val dir = File(c.appContext.cacheDir, "chorus")

    fun setMinutes(m: Int) = _state.update { it.copy(maxMinutes = m) }

    fun start() {
        if (_state.value.recording) return
        stopRequested = false
        dir.deleteRecursively(); dir.mkdirs()
        _state.update { ChorusState(recording = true, maxMinutes = it.maxMinutes, startedAt = System.currentTimeMillis()) }
        viewModelScope.launch {
            c.location.current()?.let { loc -> _state.update { it.copy(latitude = loc.latitude, longitude = loc.longitude, accuracy = loc.accuracy) } }
        }
        val blocks = Channel<Pair<Int, ShortArray>>(Channel.UNLIMITED)
        job = viewModelScope.launch {
            val total = _state.value.maxMinutes * 60
            var done = 0
            try {
                while (done < total && !stopRequested) {
                    val secs = minOf(BLOCK_SECONDS, total - done)
                    val offset = done
                    val pcm = SoundRecorder.record(BirdSongClassifier.SAMPLE_RATE, secs, { stopRequested }) { s, l ->
                        _state.update { it.copy(seconds = offset + s, level = l) }
                    }
                    if (pcm.size >= BirdSongClassifier.SAMPLE_RATE * 3) blocks.send(offset to pcm)
                    done += secs
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Recording stopped: ${e.message}") }
            } finally {
                blocks.close()
                _state.update { it.copy(recording = false) }
            }
        }
        viewModelScope.launch {
            var n = 0
            for ((offset, pcm) in blocks) {
                _state.update { it.copy(analysing = it.analysing + 1) }
                runCatching { analyse(offset, pcm, n++) }
                _state.update { it.copy(analysing = it.analysing - 1) }
            }
        }
    }

    fun stop() { stopRequested = true }

    private suspend fun analyse(offset: Int, pcm: ShortArray, index: Int) {
        val matches = c.birdSong.classify(SoundRecorder.toFloats(pcm), minScore = 0.3f, max = 15)
        if (matches.isEmpty()) return
        val placeId = c.settings.current().regionPlaceId
        val cands = c.regions.annotate(c.birdSong.toCandidates(matches), placeId)
            .filter { it.regionMatch != RegionMatch.NOT_RECORDED }
        if (cands.isEmpty()) return
        val hits = matches.associateBy { it.scientificName.lowercase() }
        var clip: File? = null
        fun clipFile(): File = clip ?: File(dir, "clip-$index.wav").also {
            SoundRecorder.writeWav(pcm, BirdSongClassifier.SAMPLE_RATE, it); clip = it
        }
        _state.update { st ->
            val map = st.heard.associateBy { it.key }.toMutableMap()
            for (cand in cands) {
                val key = cand.scientificName.trim().lowercase().split(Regex("\\s+")).take(2).joinToString(" ")
                val windows = hits[cand.scientificName.lowercase()]?.windows ?: 1
                val firstWindowAt = offset
                val prev = map[key]
                map[key] = if (prev == null) {
                    Heard(key, cand.scientificName, cand.commonName, cand.score, windows, firstWindowAt, clipFile(), cand.establishment)
                } else {
                    val better = cand.score > prev.bestScore
                    prev.copy(
                        bestScore = maxOf(prev.bestScore, cand.score),
                        clips = prev.clips + windows,
                        clip = if (better) clipFile() else prev.clip,
                        commonName = prev.commonName ?: cand.commonName,
                    )
                }
            }
            val heard = map.values.sortedByDescending { it.bestScore }
            // Tick the confident ones by default.
            val newKeys = heard.filter { h -> st.heard.none { it.key == h.key } && h.bestScore >= 0.6f }.map { it.key }
            st.copy(heard = heard, selected = st.selected + newKeys)
        }
    }

    fun toggle(key: String) = _state.update { st ->
        st.copy(selected = if (key in st.selected) st.selected - key else st.selected + key)
    }

    /** Each ticked bird becomes its own find, with the clip where it was clearest. */
    fun save(onSaved: () -> Unit) {
        val st = _state.value
        val chosen = st.heard.filter { it.key in st.selected }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val s = c.settings.current()
            for (h in chosen) {
                val file = c.photos.newSoundFile()
                h.clip.copyTo(file, overwrite = true)
                val info = runCatching { c.species.info(h.scientificName, s.regionPlaceId, s.offlineMode) }.getOrNull()
                val id = c.db.finds().insert(
                    Find(
                        photoPath = file.path,
                        createdAt = (st.startedAt ?: System.currentTimeMillis()) + h.firstAt * 1000L,
                        latitude = st.latitude,
                        longitude = st.longitude,
                        accuracyMeters = st.accuracy,
                        category = Category.BIRD.name,
                        scientificName = h.scientificName,
                        commonName = info?.commonName ?: h.commonName,
                        confidence = h.bestScore,
                        idSource = IdSource.SONG.label,
                        taxonId = info?.taxonId,
                        establishment = info?.establishment ?: h.establishment,
                        regionPlaceId = s.regionPlaceId,
                        notes = "Heard in a dawn chorus recording (${h.clips} three-second clips).",
                    ),
                )
                c.db.finds().insertPhotos(listOf(FindPhoto(findId = id, path = file.path, category = ListenViewModel.SOUND, addedAt = System.currentTimeMillis())))
            }
            dir.deleteRecursively()
            _state.value = ChorusState(maxMinutes = st.maxMinutes)
            onSaved()
        }
    }

    override fun onCleared() { stopRequested = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChorusScreen(container: AppContainer, onBack: () -> Unit, onSaved: () -> Unit) {
    val vm: ChorusViewModel = viewModel { ChorusViewModel(container) }
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.start()
    }
    val view = LocalView.current
    DisposableEffect(st.recording) {
        view.keepScreenOn = st.recording
        onDispose { view.keepScreenOn = false }
    }
    val available = remember { container.birdSong.isAvailable() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dawn chorus") },
                navigationIcon = { IconButton(onClick = { vm.stop(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!available) {
                        Text("The bird-song model isn't in this build.", color = MaterialTheme.colorScheme.error)
                        return@Column
                    }
                    Text(
                        "Put the phone down somewhere quiet and leave this screen open. It listens in 30-second clips and lists every bird " +
                            "it hears. The screen stays on while it listens. Works offline.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (st.recording) {
                        val m = st.seconds.toInt()
                        Text("Listening… %d:%02d of %d:00".format(m / 60, m % 60, st.maxMinutes), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { st.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        LinearProgressIndicator(progress = { st.seconds / (st.maxMinutes * 60f) }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = vm::stop) { Icon(Icons.Filled.Stop, null); Text("  Stop") }
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 20, 30).forEach { m ->
                                FilterChip(selected = st.maxMinutes == m, onClick = { vm.setMinutes(m) }, label = { Text("$m min") })
                            }
                        }
                        Button(enabled = !st.saving && st.analysing == 0, onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) vm.start() else askPermissions.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        }) { Icon(Icons.Filled.Mic, null); Text(if (st.startedAt == null) "  Start listening" else "  Start again") }
                    }
                    if (st.analysing > 0) Text("Identifying the last clip…", style = MaterialTheme.typography.bodySmall)
                    st.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (st.heard.isNotEmpty()) {
                        Text("Heard ${st.heard.size} species. Tick the ones to save as finds.", style = MaterialTheme.typography.titleSmall)
                    } else if (st.startedAt != null && !st.recording && st.analysing == 0) {
                        Text("No birds heard clearly. Try closer to the trees, or for longer.")
                    }
                }
            }
            items(st.heard, key = { it.key }) { h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = h.key in st.selected, onCheckedChange = { vm.toggle(h.key) })
                    Column(Modifier.weight(1f)) {
                        Text(h.commonName ?: h.scientificName, style = MaterialTheme.typography.bodyLarge)
                        if (h.commonName != null) Text(h.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(h.bestScore * 100).toInt()}% · first at %d:%02d · %d clips".format(h.firstAt / 60, h.firstAt % 60, h.clips),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        com.nativefinds.app.ui.common.SoundPlayer(h.clip.path, Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (st.heard.isNotEmpty() && !st.recording) {
                item {
                    Button(enabled = st.selected.isNotEmpty() && !st.saving && st.analysing == 0, onClick = { vm.save(onSaved) }) {
                        Text("Save ${st.selected.size} as finds")
                    }
                }
            }
        }
    }
}
