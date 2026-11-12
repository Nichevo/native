package com.nativefinds.app.id

import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.info.RegionPackRepository

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
        val annotated = regions.annotate(raw, settings.regionPlaceId).take(6)
        if (raw.isNotEmpty() && annotated.isEmpty()) messages += "No confident matches"
        return Result(annotated, messages)
    }

    suspend fun secondOpinion(photoPath: String, category: Category, settings: AppSettings): OllamaClient.Opinion {
        if (settings.offlineMode) throw IdentificationException("Offline mode is on")
        val op = ollama.identify(photoPath, category.label, settings.regionName, settings)
        return op.copy(candidates = regions.annotate(op.candidates, settings.regionPlaceId))
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
