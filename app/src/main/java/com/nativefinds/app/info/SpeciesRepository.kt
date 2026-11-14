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

    /** Fetches fresh info (ignoring the cache), for re-checking older finds. */
    suspend fun refresh(scientificName: String, placeId: Int): SpeciesInfo? =
        runCatching { inat.lookup(scientificName, placeId) }.getOrNull()?.also { db.speciesInfo().put(it) }

    /**
     * Brings every saved find's names, iNaturalist id and native status up to date. Each find keeps
     * its own species choice, date and place. Returns how many finds changed.
     */
    suspend fun refreshFinds(defaultPlaceId: Int, onProgress: (Int, Int) -> Unit): Int {
        val finds = db.finds().getAll().filter { it.scientificName != null }
        val byName = finds.groupBy { (it.scientificName!!.lowercase()) to (it.regionPlaceId ?: defaultPlaceId) }
        var done = 0
        var changed = 0
        for ((key, group) in byName) {
            val info = refresh(group.first().scientificName!!, key.second)
            if (info != null) {
                for (f in group) {
                    val updated = f.copy(
                        commonName = info.commonName ?: f.commonName,
                        taxonId = info.taxonId ?: f.taxonId,
                        establishment = info.establishment ?: f.establishment,
                    )
                    if (updated != f) { db.finds().update(updated); changed++ }
                }
            }
            done += group.size
            onProgress(done, finds.size)
            kotlinx.coroutines.delay(600) // be gentle with iNaturalist
        }
        return changed
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
