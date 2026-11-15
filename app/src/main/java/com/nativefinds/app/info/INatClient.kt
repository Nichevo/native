package com.nativefinds.app.info

import com.nativefinds.app.data.RegionSpecies
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.id.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** iNaturalist's public API (no key). Used for species info, native status and region packs. */
class INatClient {
    private val base = "https://api.inaturalist.org/v1".toHttpUrl()

    suspend fun lookup(scientificName: String, placeId: Int): SpeciesInfo? = withContext(Dispatchers.IO) {
        val search = get(
            base.newBuilder().addPathSegment("taxa")
                .addQueryParameter("q", scientificName)
                .addQueryParameter("per_page", "10")
                .addQueryParameter("locale", "en")
                .addQueryParameter("preferred_place_id", placeId.toString())
                .build(),
        )
        val results = search.optJSONArray("results") ?: return@withContext null
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        val match = all.firstOrNull { it.optString("name").equals(scientificName, ignoreCase = true) }
            ?: all.firstOrNull { it.optString("matched_term").equals(scientificName, ignoreCase = true) }
            ?: all.firstOrNull { it.optString("rank") in setOf("species", "subspecies", "variety") }
            ?: return@withContext null
        val id = match.optLong("id")

        val detail = runCatching {
            get(
                base.newBuilder().addPathSegment("taxa").addPathSegment(id.toString())
                    .addQueryParameter("locale", "en")
                    .addQueryParameter("preferred_place_id", placeId.toString())
                    .addQueryParameter("all_names", "true")
                    .build(),
            ).optJSONArray("results")?.optJSONObject(0)
        }.getOrNull() ?: match

        SpeciesInfo(
            scientificName = scientificName,
            taxonId = id,
            commonName = detail.optString("preferred_common_name").ifBlank { null },
            photoUrl = detail.optJSONObject("default_photo")?.optString("medium_url")?.ifBlank { null },
            wikipediaUrl = detail.optString("wikipedia_url").ifBlank { null },
            summary = detail.optString("wikipedia_summary").ifBlank { null }?.let(::stripHtml),
            establishment = establishmentFor(detail, placeId),
            conservationStatus = conservationFor(detail, placeId),
            placeId = placeId,
            iconicTaxon = detail.optString("iconic_taxon_name").ifBlank { null },
            fetchedAt = System.currentTimeMillis(),
            family = familyOf(detail),
            maoriName = nameInLocale(detail, "mi"),
        )
    }

    private fun familyOf(taxon: JSONObject): String? {
        val ancestors = taxon.optJSONArray("ancestors") ?: return null
        for (i in 0 until ancestors.length()) {
            val a = ancestors.optJSONObject(i) ?: continue
            if (a.optString("rank") == "family") {
                val name = a.optString("name").ifBlank { return null }
                val common = a.optString("preferred_common_name").ifBlank { null }
                return if (common != null) "$name ($common)" else name
            }
        }
        return null
    }

    private fun nameInLocale(taxon: JSONObject, locale: String): String? {
        val names = taxon.optJSONArray("names") ?: return null
        for (i in 0 until names.length()) {
            val n = names.optJSONObject(i) ?: continue
            if (n.optString("locale") == locale && n.optBoolean("is_valid", true)) {
                n.optString("name").ifBlank { null }?.let { return it }
            }
        }
        return null
    }

    /**
     * Downloads the birds and plants recorded in a place (research-grade observations), page by page,
     * then marks each as endemic / native / introduced using iNaturalist's place checklists.
     * [onProgress] gets (done, total) for the main list.
     */
    suspend fun regionSpecies(
        placeId: Int,
        maxPages: Int = 40,
        onProgress: (Int, Int) -> Unit,
    ): List<RegionSpecies> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RegionSpecies>()
        speciesCounts(placeId, null, maxPages, onProgress) { r, t ->
            val name = t.optString("name")
            if (name.isNotBlank()) {
                out += RegionSpecies(
                    placeId = placeId,
                    taxonId = t.optLong("id"),
                    scientificName = name,
                    commonName = t.optString("preferred_common_name").ifBlank { null },
                    iconicTaxon = t.optString("iconic_taxon_name").ifBlank { null },
                    photoUrl = t.optJSONObject("default_photo")?.optString("square_url")?.ifBlank { null },
                    observations = r.optInt("count"),
                    establishment = t.optJSONObject("establishment_means")?.optString("establishment_means")
                        ?.ifBlank { null }
                        ?: t.optString("preferred_establishment_means").ifBlank { null },
                )
            }
        }
        // Status passes: smaller lists, so they're quick. Endemic wins over native.
        val status = mutableMapOf<Long, String>()
        for (flag in listOf("introduced", "native", "endemic")) {
            runCatching {
                speciesCounts(placeId, flag, maxPages, { _, _ -> }) { _, t -> status[t.optLong("id")] = flag }
            }
        }
        out.map { rs -> status[rs.taxonId]?.let { rs.copy(establishment = it) } ?: rs }
    }

    private suspend fun speciesCounts(
        placeId: Int,
        statusFlag: String?,
        maxPages: Int,
        onProgress: (Int, Int) -> Unit,
        onTaxon: (JSONObject, JSONObject) -> Unit,
    ) {
        var page = 1
        var seen = 0
        var total = Int.MAX_VALUE
        while (page <= maxPages && seen < total) {
            val url = base.newBuilder().addPathSegments("observations/species_counts")
                .addQueryParameter("place_id", placeId.toString())
                .addQueryParameter("iconic_taxa", ICONIC_TAXA)
                .addQueryParameter("quality_grade", "research")
                .addQueryParameter("locale", "en")
                .addQueryParameter("preferred_place_id", placeId.toString())
                .addQueryParameter("per_page", "500")
                .addQueryParameter("page", page.toString())
            if (statusFlag != null) url.addQueryParameter(statusFlag, "true")
            val json = get(url.build())
            total = json.optInt("total_results", 0)
            val results = json.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val t = r.optJSONObject("taxon") ?: continue
                onTaxon(r, t)
            }
            seen += results.length()
            onProgress(seen, minOf(total, maxPages * 500))
            page++
            delay(1_100) // stay well under iNaturalist's ~1 request/second guidance
        }
    }

    /** Birds and plants recorded within [radiusKm] of a point, most-seen first. [sinceDays] null = all time. */
    suspend fun nearbySpecies(
        lat: Double,
        lng: Double,
        radiusKm: Int,
        sinceDays: Int?,
        placeId: Int,
    ): List<NearbySpecies> = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("observations/species_counts")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lng", lng.toString())
            .addQueryParameter("radius", radiusKm.toString())
            .addQueryParameter("iconic_taxa", ICONIC_TAXA)
            .addQueryParameter("quality_grade", "research")
            .addQueryParameter("locale", "en")
            .addQueryParameter("preferred_place_id", placeId.toString())
            .addQueryParameter("per_page", "500")
        sinceDays?.let { url.addQueryParameter("d1", isoDaysAgo(it)) }
        val results = get(url.build()).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val t = r.optJSONObject("taxon") ?: return@mapNotNull null
            NearbySpecies(
                taxonId = t.optLong("id"),
                scientificName = t.optString("name").ifBlank { return@mapNotNull null },
                commonName = t.optString("preferred_common_name").ifBlank { null },
                iconicTaxon = t.optString("iconic_taxon_name").ifBlank { null },
                photoUrl = t.optJSONObject("default_photo")?.optString("square_url")?.ifBlank { null },
                count = r.optInt("count"),
            )
        }
    }

    /** Individual recent sightings (with coordinates) within [radiusKm] of a point, for the map. */
    suspend fun recentSightings(
        lat: Double,
        lng: Double,
        radiusKm: Int,
        sinceDays: Int,
        placeId: Int,
    ): List<Sighting> = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("observations")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lng", lng.toString())
            .addQueryParameter("radius", radiusKm.toString())
            .addQueryParameter("iconic_taxa", ICONIC_TAXA)
            .addQueryParameter("quality_grade", "research")
            .addQueryParameter("geoprivacy", "open")
            .addQueryParameter("d1", isoDaysAgo(sinceDays))
            .addQueryParameter("order_by", "observed_on")
            .addQueryParameter("locale", "en")
            .addQueryParameter("preferred_place_id", placeId.toString())
            .addQueryParameter("per_page", "200")
        val results = get(url.build()).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val t = o.optJSONObject("taxon") ?: return@mapNotNull null
            val coords = o.optJSONObject("geojson")?.optJSONArray("coordinates") ?: return@mapNotNull null
            Sighting(
                id = o.optLong("id"),
                latitude = coords.optDouble(1),
                longitude = coords.optDouble(0),
                scientificName = t.optString("name"),
                commonName = t.optString("preferred_common_name").ifBlank { null },
                observedOn = o.optString("observed_on").ifBlank { null },
            )
        }
    }

    private fun isoDaysAgo(days: Int): String =
        java.time.LocalDate.now().minusDays(days.toLong()).toString()

    private fun get(url: HttpUrl): JSONObject {
        val req = Request.Builder().url(url).get().build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("iNaturalist error ${resp.code}")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun establishmentFor(taxon: JSONObject, placeId: Int): String? {
        taxon.optJSONObject("establishment_means")?.let { em ->
            val p = em.optJSONObject("place")?.optInt("id")
            if (p == null || p == placeId) em.optString("establishment_means").ifBlank { null }?.let { return it }
        }
        val listed = taxon.optJSONArray("listed_taxa") ?: return null
        for (i in 0 until listed.length()) {
            val lt = listed.optJSONObject(i) ?: continue
            val p = lt.optJSONObject("place")?.optInt("id") ?: lt.optInt("place_id")
            if (p == placeId) lt.optString("establishment_means").ifBlank { null }?.let { return it }
        }
        return null
    }

    private fun conservationFor(taxon: JSONObject, placeId: Int): String? {
        val list = taxon.optJSONArray("conservation_statuses")
        if (list != null) {
            for (i in 0 until list.length()) {
                val cs = list.optJSONObject(i) ?: continue
                val p = cs.optJSONObject("place")?.optInt("id") ?: cs.optInt("place_id")
                if (p == placeId) {
                    return cs.optString("status_name").ifBlank { cs.optString("status") }.ifBlank { null }
                }
            }
        }
        return taxon.optJSONObject("conservation_status")?.optString("status_name")?.ifBlank { null }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ").trim()
}

data class NearbySpecies(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val iconicTaxon: String?,
    val photoUrl: String?,
    val count: Int,
)

data class Sighting(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val scientificName: String,
    val commonName: String?,
    val observedOn: String?,
)

/** What region packs, nearby sightings and the library cover: birds, plants, insects, spiders and reptiles. */
const val ICONIC_TAXA = "Aves,Plantae,Insecta,Arachnida,Reptilia"
