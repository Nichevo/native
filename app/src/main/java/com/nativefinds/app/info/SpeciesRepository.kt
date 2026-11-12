package com.nativefinds.app.info

import com.nativefinds.app.data.AppDatabase
import com.nativefinds.app.data.SpeciesInfo

/** Species info with a local cache, so pages work offline once seen. */
class SpeciesRepository(private val db: AppDatabase, private val inat: INatClient) {
    private val maxAgeMs = 30L * 24 * 60 * 60 * 1000

    suspend fun info(scientificName: String, placeId: Int, offline: Boolean): SpeciesInfo? {
        val cached = db.speciesInfo().get(scientificName)
        val fresh = cached != null && cached.placeId == placeId &&
            System.currentTimeMillis() - cached.fetchedAt < maxAgeMs
        if (offline || fresh) return cached ?: fromRegionPack(scientificName, placeId)
        return runCatching { inat.lookup(scientificName, placeId) }.getOrNull()
            ?.also { db.speciesInfo().put(it) }
            ?: cached
            ?: fromRegionPack(scientificName, placeId)
    }

    /** Minimal info from a downloaded region pack (offline fallback). */
    private suspend fun fromRegionPack(name: String, placeId: Int): SpeciesInfo? {
        val rs = db.regions().findSpeciesOrSub(placeId, name) ?: return null
        return SpeciesInfo(
            scientificName = name,
            taxonId = rs.taxonId,
            commonName = rs.commonName,
            photoUrl = rs.photoUrl,
            wikipediaUrl = null,
            summary = null,
            establishment = rs.establishment,
            conservationStatus = null,
            placeId = placeId,
            iconicTaxon = rs.iconicTaxon,
            fetchedAt = 0,
        )
    }
}
