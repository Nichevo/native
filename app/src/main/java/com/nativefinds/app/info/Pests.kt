package com.nativefinds.app.info

import android.content.Context
import com.nativefinds.app.data.Regions

enum class PestKind { MPI, PEST }

data class Pest(val scientificName: String, val kind: PestKind, val commonName: String)

/**
 * Bundled pest lists (assets/pests/<region>.csv). Only New Zealand has one for now.
 * Matching is by "genus species", or by genus for "Genus *" entries.
 */
class PestList(private val context: Context) {
    private val byRegion = mutableMapOf<Int, Map<String, Pest>>()

    private fun load(placeId: Int): Map<String, Pest> = synchronized(byRegion) {
        byRegion.getOrPut(placeId) {
            val file = when (placeId) {
                Regions.NEW_ZEALAND.placeId -> "pests/nz.csv"
                else -> return@getOrPut emptyMap()
            }
            runCatching {
                context.assets.open(file).bufferedReader().readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val parts = line.split(",", limit = 3)
                        if (parts.size < 2) return@mapNotNull null
                        val kind = if (parts[1].trim() == "mpi") PestKind.MPI else PestKind.PEST
                        Pest(parts[0].trim(), kind, parts.getOrNull(2)?.trim().orEmpty())
                    }
                    .associateBy { keyOf(it.scientificName) }
            }.getOrDefault(emptyMap())
        }
    }

    fun lookup(scientificName: String?, placeId: Int): Pest? {
        if (scientificName.isNullOrBlank()) return null
        val list = load(placeId)
        if (list.isEmpty()) return null
        val key = keyOf(scientificName)
        return list[key] ?: list[key.substringBefore(' ') + " *"]
    }

    private fun keyOf(name: String): String {
        val words = name.trim().lowercase().replace("×", "x").split(Regex("\\s+"))
        // "Crocosmia x crocosmiiflora" keeps the hybrid marker so it stays a 3-word key
        return if (words.getOrNull(1) == "x") words.take(3).joinToString(" ") else words.take(2).joinToString(" ")
    }
}
