package com.nativefinds.app.ui.identify

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.Category
import com.nativefinds.app.id.IdSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class IdentifyState(
    val category: Category = Category.LEAF,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val locating: Boolean = false,
    val identifying: Boolean = false,
    val candidates: List<Candidate> = emptyList(),
    val messages: List<String> = emptyList(),
    val askingPc: Boolean = false,
    val pcNotes: String? = null,
    val selected: Candidate? = null,
    val selectedInfo: SpeciesInfo? = null,
    val loadingInfo: Boolean = false,
    val notes: String = "",
    val saving: Boolean = false,
    val settings: AppSettings = AppSettings(),
)

class IdentifyViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private var pendingCapture: File? = null

    init {
        viewModelScope.launch { c.settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
    }

    fun setCategory(cat: Category) {
        _state.update { it.copy(category = cat) }
        if (_state.value.photoPath != null) identify()
    }

    fun newCaptureUri(): Uri {
        val (file, uri) = c.photos.newCaptureTarget()
        pendingCapture = file
        return uri
    }

    fun onCaptured(success: Boolean) {
        val f = pendingCapture ?: return
        if (!success || !f.exists() || f.length() == 0L) return
        importAndIdentify(Uri.fromFile(f), exifLocation = null)
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        importAndIdentify(uri, exifLocation = c.photos.readExifLocation(uri))
    }

    private fun importAndIdentify(uri: Uri, exifLocation: Pair<Double, Double>?) {
        viewModelScope.launch {
            val old = _state.value.photoPath
            val file = runCatching { withContext(Dispatchers.IO) { c.photos.importPhoto(uri) } }.getOrElse { e ->
                _state.update { it.copy(messages = listOf("Couldn't read the photo: ${e.message}")) }
                return@launch
            }
            old?.let { c.photos.delete(it) }
            c.photos.clearCaptures()
            _state.update {
                it.copy(
                    photoPath = file.absolutePath, candidates = emptyList(), selected = null, selectedInfo = null,
                    pcNotes = null, messages = emptyList(),
                )
            }
            if (exifLocation != null) {
                _state.update { it.copy(latitude = exifLocation.first, longitude = exifLocation.second, accuracy = null) }
            } else {
                locate()
            }
            identify()
        }
    }

    fun locate() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true) }
            val loc = c.location.current()
            _state.update {
                it.copy(
                    locating = false,
                    latitude = loc?.latitude ?: it.latitude,
                    longitude = loc?.longitude ?: it.longitude,
                    accuracy = loc?.accuracy ?: it.accuracy,
                )
            }
        }
    }

    fun identify() {
        val path = _state.value.photoPath ?: return
        viewModelScope.launch {
            _state.update { it.copy(identifying = true, candidates = emptyList(), messages = emptyList(), selected = null) }
            val s = c.settings.current()
            val r = c.identifier.identify(path, _state.value.category, s)
            _state.update { it.copy(identifying = false, candidates = r.candidates, messages = r.messages) }
        }
    }

    fun askPc() {
        val path = _state.value.photoPath ?: return
        viewModelScope.launch {
            _state.update { it.copy(askingPc = true) }
            val s = c.settings.current()
            val result = runCatching { c.identifier.secondOpinion(path, _state.value.category, s) }
            _state.update { st ->
                result.fold(
                    onSuccess = { op ->
                        val others = st.candidates.filterNot { it.source == IdSource.PC_AI }
                        st.copy(
                            askingPc = false,
                            candidates = op.candidates + others,
                            pcNotes = op.notes,
                            messages = if (op.candidates.isEmpty()) st.messages + "Your PC couldn't name a species" else st.messages,
                        )
                    },
                    onFailure = { e -> st.copy(askingPc = false, messages = st.messages + (e.message ?: "PC AI failed")) },
                )
            }
        }
    }

    fun select(candidate: Candidate?) {
        _state.update { it.copy(selected = candidate, selectedInfo = null) }
        if (candidate == null) return
        viewModelScope.launch {
            _state.update { it.copy(loadingInfo = true) }
            val s = c.settings.current()
            val info = c.species.info(candidate.scientificName, s.regionPlaceId, s.offlineMode)
            _state.update { if (it.selected == candidate) it.copy(selectedInfo = info, loadingInfo = false) else it }
        }
    }

    fun enterManually(name: String) {
        if (name.isBlank()) return
        select(Candidate(scientificName = name.trim(), commonName = null, score = 1f, source = IdSource.MANUAL))
    }

    fun setNotes(n: String) = _state.update { it.copy(notes = n) }

    fun save(onSaved: (Long) -> Unit) {
        val st = _state.value
        val path = st.photoPath ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val sel = st.selected
            val info = st.selectedInfo
            val id = c.db.finds().insert(
                Find(
                    photoPath = path,
                    createdAt = System.currentTimeMillis(),
                    latitude = st.latitude,
                    longitude = st.longitude,
                    accuracyMeters = st.accuracy,
                    category = st.category.name,
                    scientificName = sel?.scientificName,
                    commonName = info?.commonName ?: sel?.commonName,
                    confidence = sel?.score,
                    idSource = sel?.source?.label,
                    taxonId = info?.taxonId,
                    establishment = info?.establishment ?: sel?.establishment,
                    regionPlaceId = st.settings.regionPlaceId,
                    notes = listOfNotNull(st.notes.takeIf { it.isNotBlank() }, st.pcNotes?.let { "PC AI: $it" })
                        .joinToString("\n\n"),
                ),
            )
            _state.value = IdentifyState(settings = st.settings) // photo now belongs to the find
            onSaved(id)
        }
    }

    /** Called when leaving without saving: remove the unsaved photo. */
    fun discard() {
        _state.value.photoPath?.let { c.photos.delete(it) }
        c.photos.clearCaptures()
        _state.update { IdentifyState(settings = it.settings) }
    }
}
