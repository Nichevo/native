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

    suspend fun identify(photoPath: String, category: Category, settings: AppSettings): Result {
        val messages = mutableListOf<String>()
        val raw: List<Candidate> = when {
            category == Category.BIRD -> tryOrNote(messages) {
                onDevice.classify(photoPath, OnDeviceClassifier.Model.BIRDS)
            }

            category.isPlant -> {
                val online = !settings.offlineMode && settings.plantNetApiKey.isNotBlank()
                val fromPlantNet = if (online) {
                    tryOrNote(messages) { plantNet.identify(photoPath, category.plantNetOrgan ?: "auto", settings.plantNetApiKey) }
                } else {
                    if (!settings.offlineMode) messages += "No Pl@ntNet key yet, so using the on-phone plant model"
                    emptyList()
                }
                fromPlantNet.ifEmpty {
                    tryOrNote(messages) { onDevice.classify(photoPath, OnDeviceClassifier.Model.PLANTS) }
                }
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

    suspend fun secondOpinion(photoPath: String, category: Category, settings: AppSettings): OllamaClient.Opinion {
        if (settings.offlineMode) throw IdentificationException("Offline mode is on")
        val op = ollama.identify(photoPath, category.label, settings.regionName, settings)
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
