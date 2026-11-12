package com.nativefinds.app.id

import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.info.RegionPackRepository
import com.nativefinds.app.info.SpeciesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Picks the right identifier for the category and settings:
 * - Plants: Pl@ntNet online, or the on-phone plant model when offline / no key / Pl@ntNet fails.
 * - Birds: the on-phone bird model.
 * - Anything else: no automatic ID (use the PC AI second opinion).
 * Results are annotated against the region pack if one is downloaded.
 */
class Identifier(
    private val plantNet: PlantNetClient,
    private val onDevice: OnDeviceClassifier,
    private val ollama: OllamaClient,
    private val regions: RegionPackRepository,
    private val species: SpeciesRepository,
) {
    data class Result(val candidates: List<Candidate>, val messages: List<String>)

    /** One photo of the find and what it shows (leaf, flower, bird...). */
    data class Photo(val path: String, val category: Category)

    /**
     * Identifies a find from all its photos. Plants go to Pl@ntNet together (it combines organs);
     * on-phone results from several photos are combined so agreeing photos raise the score.
     */
    suspend fun identify(photos: List<Photo>, settings: AppSettings): Result {
        val messages = mutableListOf<String>()
        if (photos.isEmpty()) return Result(emptyList(), messages)
        val main = photos.first().category
        val raw: List<Candidate> = when {
            main == Category.BIRD -> onDeviceCombined(photos, OnDeviceClassifier.Model.BIRDS, messages)

            main.isPlant -> {
                val plantPhotos = photos.filter { it.category.isPlant }
                val online = !settings.offlineMode && settings.plantNetApiKey.isNotBlank()
                val fromPlantNet = if (online) {
                    tryOrNote(messages) {
                        plantNet.identify(plantPhotos.map { it.path to (it.category.plantNetOrgan ?: "auto") }, settings.plantNetApiKey)
                    }
                } else {
                    if (!settings.offlineMode) messages += "No Pl@ntNet key yet, so using the on-phone plant model"
                    emptyList()
                }
                fromPlantNet.ifEmpty { onDeviceCombined(plantPhotos, OnDeviceClassifier.Model.PLANTS, messages) }
            }

            else -> {
                messages += "No automatic identifier for this category. Try asking your PC."
                emptyList()
            }
        }
        val merged = mergeSubspecies(raw)
        val annotated = regions.annotate(merged, settings.regionPlaceId).take(5)
        if (raw.isNotEmpty() && annotated.isEmpty()) messages += "No confident matches"
        return Result(enrich(annotated, settings), messages)
    }

    /**
     * Runs the on-phone model on each photo and combines them: a species' combined score is
     * 1 - Π(1 - pᵢ), so independent photos that agree push the score up.
     */
    private suspend fun onDeviceCombined(
        photos: List<Photo>,
        model: OnDeviceClassifier.Model,
        messages: MutableList<String>,
    ): List<Candidate> {
        val perPhoto = photos.map { p ->
            mergeSubspecies(tryOrNote(messages) { onDevice.classify(p.path, model) })
        }
        if (perPhoto.size == 1) return perPhoto[0]
        return perPhoto.flatten()
            .groupBy { it.scientificName.lowercase() }
            .map { (_, group) ->
                val miss = group.fold(1.0) { acc, c -> acc * (1.0 - c.score.coerceIn(0f, 0.999f)) }
                group.maxBy { it.score }.copy(score = (1.0 - miss).toFloat())
            }
            .sortedByDescending { it.score }
    }

    /**
     * Models often list a subspecies and its species separately ("Porphyrio melanotus melanotus" and
     * "Porphyrio melanotus"). Fold them into one species entry with the combined score.
     */
    private fun mergeSubspecies(cands: List<Candidate>): List<Candidate> =
        cands.groupBy { speciesOf(it.scientificName).lowercase() }
            .map { (_, group) ->
                val best = group.maxBy { it.score }
                best.copy(
                    scientificName = speciesOf(best.scientificName),
                    score = group.sumOf { it.score.toDouble() }.toFloat().coerceAtMost(1f),
                    commonName = group.firstNotNullOfOrNull { it.commonName },
                )
            }
            .sortedByDescending { it.score }

    /** "Genus species subspecies" -> "Genus species"; anything else (hybrids, "var.", genus only) unchanged. */
    private fun speciesOf(name: String): String {
        val parts = name.trim().split(Regex("\\s+"))
        val epithet = Regex("[a-z][a-z-]+")
        return if (parts.size == 3 && epithet.matches(parts[1]) && epithet.matches(parts[2])) {
            parts.take(2).joinToString(" ")
        } else {
            name.trim()
        }
    }

    /** Adds common and Māori names, a photo and native status to each match (cached, so cheap). */
    private suspend fun enrich(cands: List<Candidate>, settings: AppSettings): List<Candidate> = coroutineScope {
        cands.map { c ->
            async {
                val info = runCatching { species.info(c.scientificName, settings.regionPlaceId, settings.offlineMode) }.getOrNull()
                    ?: return@async c
                c.copy(
                    commonName = info.commonName ?: c.commonName,
                    photoUrl = info.photoUrl,
                    establishment = info.establishment ?: c.establishment,
                    maoriName = info.maoriName,
                    family = c.family ?: info.family,
                )
            }
        }.awaitAll()
    }

    suspend fun secondOpinion(photos: List<Photo>, settings: AppSettings): OllamaClient.Opinion {
        if (settings.offlineMode) throw IdentificationException("Offline mode is on")
        val label = photos.map { it.category.label }.distinct().joinToString(", ")
        val op = ollama.identify(photos.map { it.path }, label, settings.regionName, settings)
        return op.copy(candidates = enrich(regions.annotate(op.candidates, settings.regionPlaceId), settings))
    }

    private suspend fun tryOrNote(messages: MutableList<String>, block: suspend () -> List<Candidate>): List<Candidate> =
        try {
            block()
        } catch (e: IdentificationException) {
            messages += e.message ?: "Identification failed"
            emptyList()
        } catch (e: Exception) {
            messages += "Identification failed: ${e.message ?: e.javaClass.simpleName}"
            emptyList()
        }
}
