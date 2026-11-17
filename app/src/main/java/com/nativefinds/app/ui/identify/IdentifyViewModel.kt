package com.nativefinds.app.ui.identify

import com.nativefinds.app.audio.isAudioPath
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.data.Find
import com.nativefinds.app.data.FindPhoto
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.Category
import com.nativefinds.app.id.IdSource
import com.nativefinds.app.id.Identifier
import com.nativefinds.app.id.PcQueueWorker
import com.nativefinds.app.id.PcUnreachableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A photo in the find being built. [isNew] photos are deleted if you leave without saving. */
data class PhotoItem(val path: String, val category: Category, val isNew: Boolean)

data class IdentifyState(
    /** Set when editing an existing find instead of creating one. */
    val findId: Long? = null,
    val createdAt: Long? = null,
    /** Category for the next photo you add. */
    val category: Category = Category.LEAF,
    val photos: List<PhotoItem> = emptyList(),
    val removedPaths: List<String> = emptyList(),
    /** Sound recordings already on the find (kept as they are when editing). */
    val sounds: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val locating: Boolean = false,
    val identifying: Boolean = false,
    val candidates: List<Candidate> = emptyList(),
    /** Scores from the previous run, keyed by lower-case scientific name, to show how they moved. */
    val previousScores: Map<String, Float> = emptyMap(),
    val messages: List<String> = emptyList(),
    val askingPc: Boolean = false,
    val pcUnreachable: Boolean = false,
    val pcNotes: String? = null,
    val selected: Candidate? = null,
    val selectedInfo: SpeciesInfo? = null,
    val loadingInfo: Boolean = false,
    val notes: String = "",
    val saving: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Identifications made in this session, saved with the find so you can look back at them. */
    val runs: List<com.nativefinds.app.data.IdRun> = emptyList(),
) {
    val hasPhotos get() = photos.isNotEmpty()
    /** Photos or a kept sound recording: enough to save. */
    val hasMedia get() = photos.isNotEmpty() || sounds.isNotEmpty()
    val mainCategory get() = photos.firstOrNull()?.category ?: category
}

class IdentifyViewModel(private val c: AppContainer, private val editFindId: Long?) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private var pendingCapture: File? = null
    private var original: Find? = null
    /** What prompted the next identification, for its history entry. */
    private var nextLabel: String? = null

    init {
        viewModelScope.launch { c.settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        if (editFindId != null) viewModelScope.launch { loadForEdit(editFindId) }
    }

    private suspend fun loadForEdit(id: Long) {
        val f = c.db.finds().get(id) ?: return
        original = f
        val cat = runCatching { Category.valueOf(f.category) }.getOrDefault(Category.OTHER)
        val stored = c.db.finds().photos(id)
        val sounds = stored.filter { isAudioPath(it.path) }.map { it.path }
            .ifEmpty { listOfNotNull(f.photoPath.takeIf(::isAudioPath)) }
        val photos = stored.filterNot { isAudioPath(it.path) }.map {
            PhotoItem(it.path, runCatching { Category.valueOf(it.category) }.getOrDefault(cat), isNew = false)
        }.ifEmpty { listOfNotNull(PhotoItem(f.photoPath, cat, isNew = false).takeUnless { isAudioPath(f.photoPath) }) }
        val sel = f.scientificName?.let {
            Candidate(it, f.commonName, f.confidence ?: 1f, IdSource.entries.firstOrNull { s -> s.label == f.idSource } ?: IdSource.MANUAL)
        }
        _state.update {
            it.copy(
                findId = id, createdAt = f.createdAt, category = cat, photos = photos, sounds = sounds,
                latitude = f.latitude, longitude = f.longitude, accuracy = f.accuracyMeters,
                notes = f.notes, selected = sel,
            )
        }
        sel?.let { select(it) }
    }

    fun setCategory(cat: Category) {
        _state.update { it.copy(category = cat) }
        // With a single photo, changing the category re-labels that photo and re-runs identification.
        val st = _state.value
        if (st.photos.size == 1 && st.photos[0].category != cat) {
            _state.update { it.copy(photos = listOf(it.photos[0].copy(category = cat))) }
            nextLabel = "Changed the photo to ${cat.label.lowercase()}"
            identify()
        }
    }

    /** Chooses what the next photo will show, without relabelling photos already taken. */
    fun setNextCategory(cat: Category) = _state.update { it.copy(category = cat) }

    fun newCaptureUri(): Uri {
        val (file, uri) = c.photos.newCaptureTarget()
        pendingCapture = file
        return uri
    }

    fun onCaptured(success: Boolean) {
        val f = pendingCapture ?: return
        if (!success || !f.exists() || f.length() == 0L) return
        viewModelScope.launch {
            if (_state.value.settings.saveToGallery) {
                val ok = withContext(Dispatchers.IO) { c.photos.saveToGallery(f) }
                if (!ok) _state.update { it.copy(messages = it.messages + "Couldn't also save the photo to your gallery") }
            }
            addPhoto(Uri.fromFile(f), exifLocation = null)
        }
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        addPhoto(uri, exifLocation = c.photos.readExifLocation(uri))
    }

    private fun addPhoto(uri: Uri, exifLocation: Pair<Double, Double>?) {
        viewModelScope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { c.photos.importPhoto(uri) } }.getOrElse { e ->
                _state.update { it.copy(messages = listOf("Couldn't read the photo: ${e.message}")) }
                return@launch
            }
            c.photos.clearCaptures()
            val first = !_state.value.hasPhotos
            _state.update { it.copy(photos = it.photos + PhotoItem(file.absolutePath, it.category, isNew = true)) }
            val n = _state.value.photos.size
            nextLabel = if (n == 1) "First photo: ${_state.value.category.label.lowercase()}" else "Added photo $n: ${_state.value.category.label.lowercase()}"
            // The find's place is where the first photo was taken; editing keeps the original place.
            if (first && _state.value.findId == null) {
                if (exifLocation != null) {
                    _state.update { it.copy(latitude = exifLocation.first, longitude = exifLocation.second, accuracy = null) }
                } else {
                    locate()
                }
            }
            identify()
        }
    }

    fun removePhoto(path: String) {
        val st = _state.value
        val item = st.photos.firstOrNull { it.path == path } ?: return
        if (item.isNew) c.photos.delete(path)
        _state.update {
            it.copy(
                photos = it.photos.filterNot { p -> p.path == path },
                removedPaths = if (item.isNew) it.removedPaths else it.removedPaths + path,
            )
        }
        nextLabel = "Removed a photo (${_state.value.photos.size} left)"
        if (_state.value.hasPhotos) identify() else _state.update { it.copy(candidates = emptyList()) }
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

    private fun currentPhotos() = _state.value.photos.map { Identifier.Photo(it.path, it.category) }

    fun identify() {
        if (!_state.value.hasPhotos) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    identifying = true,
                    previousScores = it.candidates.associate { c -> c.scientificName.lowercase() to c.score },
                    messages = emptyList(),
                )
            }
            val s = c.settings.current()
            val r = c.identifier.identify(currentPhotos(), s)
            val keepSelection = _state.value.selected?.source == IdSource.MANUAL
            val label = nextLabel ?: "Identified again with ${_state.value.photos.size} photo${if (_state.value.photos.size == 1) "" else "s"}"
            nextLabel = null
            val run = com.nativefinds.app.id.IdHistory.run(
                0, System.currentTimeMillis(), com.nativefinds.app.id.IdHistory.AUTO, label, r.method, r.candidates, r.perPhoto,
            )
            _state.update {
                it.copy(
                    identifying = false, candidates = r.candidates, messages = r.messages,
                    runs = if (r.candidates.isEmpty() && r.method == null) it.runs else it.runs + run,
                )
            }
            if (!keepSelection) r.candidates.firstOrNull()?.let { select(it) }
        }
    }

    fun askPc() {
        if (!_state.value.hasPhotos) return
        viewModelScope.launch {
            _state.update { it.copy(askingPc = true, pcUnreachable = false) }
            val s = c.settings.current()
            val result = runCatching { c.identifier.secondOpinion(currentPhotos(), s) }
            _state.update { st ->
                result.fold(
                    onSuccess = { op ->
                        val others = st.candidates.filterNot { it.source == IdSource.PC_AI }
                        val n = st.photos.size
                        val pcRun = com.nativefinds.app.id.IdHistory.run(
                            0, System.currentTimeMillis(), com.nativefinds.app.id.IdHistory.PC, "Asked my PC",
                            "Your PC's AI model (${s.pcAiModel}) looked at all $n photo${if (n == 1) "" else "s"} together. " +
                                "Its % is the model's own estimate of how sure it is, not a calculated probability." +
                                (op.notes?.let { " It said: $it" } ?: ""),
                            op.candidates,
                        )
                        st.copy(
                            askingPc = false,
                            runs = st.runs + pcRun,
                            candidates = op.candidates + others,
                            pcNotes = op.notes,
                            messages = if (op.candidates.isEmpty()) st.messages + "Your PC couldn't name a species" else st.messages,
                        )
                    },
                    onFailure = { e ->
                        st.copy(
                            askingPc = false,
                            pcUnreachable = e is PcUnreachableException,
                            messages = st.messages + (e.message ?: "PC AI failed"),
                        )
                    },
                )
            }
            result.getOrNull()?.candidates?.firstOrNull()?.let { select(it) }
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

    /** Saves the find. With [askPcLater], it's queued for your PC to look at when it's reachable. */
    fun save(askPcLater: Boolean = false, onSaved: (Long) -> Unit) {
        val st = _state.value
        if (!st.hasMedia) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val sel = st.selected
            val info = st.selectedInfo
            val notes = listOfNotNull(st.notes.takeIf { it.isNotBlank() }, st.pcNotes?.let { "PC AI: $it" })
                .joinToString("\n\n")
            val base = original
            val find = Find(
                id = st.findId ?: 0,
                photoPath = st.photos.firstOrNull()?.path ?: st.sounds.first(),
                createdAt = st.createdAt ?: System.currentTimeMillis(),
                latitude = st.latitude,
                longitude = st.longitude,
                accuracyMeters = st.accuracy,
                category = st.mainCategory.name,
                scientificName = sel?.scientificName,
                commonName = info?.commonName ?: sel?.commonName,
                confidence = sel?.score,
                idSource = sel?.source?.label,
                taxonId = info?.taxonId,
                establishment = info?.establishment ?: sel?.establishment,
                regionPlaceId = base?.regionPlaceId ?: st.settings.regionPlaceId,
                notes = notes,
                pendingPc = askPcLater || (base?.pendingPc ?: false),
            )
            val id = if (st.findId == null) {
                c.db.finds().insert(find)
            } else {
                c.db.finds().update(find)
                c.db.finds().deletePhotos(st.findId)
                st.findId
            }
            val now = System.currentTimeMillis()
            c.db.finds().insertPhotos(
                st.photos.mapIndexed { i, p -> FindPhoto(findId = id, path = p.path, category = p.category.name, addedAt = now + i) } +
                    st.sounds.mapIndexed { i, p -> FindPhoto(findId = id, path = p, category = "SOUND", addedAt = now + st.photos.size + i) },
            )
            st.removedPaths.forEach { c.photos.delete(it) }
            // Keep this session's identifications, and note the species you chose if it wasn't simply the top match.
            val lastTop = st.runs.lastOrNull { it.kind != com.nativefinds.app.id.IdHistory.CHOICE }
                ?.let { com.nativefinds.app.id.IdHistory.candidates(it).firstOrNull()?.scientificName }
            val changed = sel != null && if (base == null) !sel.scientificName.equals(lastTop, ignoreCase = true)
                else !sel.scientificName.equals(base.scientificName, ignoreCase = true)
            val choice = if (changed && sel != null) listOf(
                com.nativefinds.app.id.IdHistory.run(
                    id, now, com.nativefinds.app.id.IdHistory.CHOICE,
                    (if (base?.scientificName != null) "You changed it to " else "You chose ") + (info?.commonName ?: sel.commonName ?: sel.scientificName),
                    if (sel.source == IdSource.MANUAL) "Entered by hand." else "Picked from the matches (${(sel.score * 100).toInt()}% from ${sel.source.label}).",
                    emptyList(), chosen = sel.scientificName,
                ),
            ) else emptyList()
            c.db.idRuns().insert(st.runs.map { it.copy(findId = id) } + choice)
            if (find.pendingPc) PcQueueWorker.runNow(c.appContext)
            _state.value = IdentifyState(settings = st.settings) // photos now belong to the find
            onSaved(id)
        }
    }

    /** Leaving without saving: remove photos added in this session only. */
    fun discard() {
        _state.value.photos.filter { it.isNew }.forEach { c.photos.delete(it.path) }
        c.photos.clearCaptures()
        _state.update { IdentifyState(settings = it.settings) }
    }
}
