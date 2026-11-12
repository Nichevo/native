package com.nativefinds.app.info

import androidx.room.withTransaction
import com.nativefinds.app.data.AppDatabase
import com.nativefinds.app.data.RegionPack
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.RegionMatch

class RegionPackRepository(private val db: AppDatabase, private val inat: INatClient) {
    val packs = db.regions().observePacks()

    suspend fun download(placeId: Int, name: String, onProgress: (Int, Int) -> Unit): Int {
        val species = inat.regionSpecies(placeId, onProgress = onProgress)
        if (species.isEmpty()) error("No species found for this place")
        db.withTransaction {
            db.regions().clearSpecies(placeId)
            db.regions().putSpecies(species)
            db.regions().putPack(RegionPack(placeId, name, System.currentTimeMillis(), species.size))
        }
        return species.size
    }

    suspend fun delete(placeId: Int) {
        db.withTransaction {
            db.regions().clearSpecies(placeId)
            db.regions().deletePack(placeId)
        }
    }

    suspend fun hasPack(placeId: Int) = db.regions().getPack(placeId) != null

    /**
     * Marks each candidate as recorded / not recorded in the region and fills in local common names,
     * then puts recorded species first (keeping score order within each group).
     */
    suspend fun annotate(candidates: List<Candidate>, placeId: Int): List<Candidate> {
        if (!hasPack(placeId)) return candidates
        val annotated = candidates.map { c ->
            val hit = db.regions().findSpeciesOrSub(placeId, c.scientificName)
            when {
                hit != null -> c.copy(
                    regionMatch = RegionMatch.RECORDED,
                    commonName = hit.commonName ?: c.commonName,
                    establishment = hit.establishment ?: c.establishment,
                )
                // Genus-only labels: recorded if any species of that genus is.
                !c.scientificName.contains(' ') &&
                    db.regions().countGenus(placeId, c.scientificName) > 0 -> c.copy(regionMatch = RegionMatch.RECORDED)
                else -> c.copy(regionMatch = RegionMatch.NOT_RECORDED)
            }
        }
        return annotated.sortedWith(
            compareBy<Candidate> { if (it.regionMatch == RegionMatch.NOT_RECORDED) 1 else 0 }
                .thenByDescending { it.score },
        )
    }
}
