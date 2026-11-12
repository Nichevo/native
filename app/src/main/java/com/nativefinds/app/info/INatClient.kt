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
        )
    }

    /**
     * Downloads the birds and plants recorded in a place (research-grade observations), page by page.
     * [onProgress] gets (done, total).
     */
    suspend fun regionSpecies(
        placeId: Int,
        maxPages: Int = 20,
        onProgress: (Int, Int) -> Unit,
    ): List<RegionSpecies> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RegionSpecies>()
        var page = 1
        var total = Int.MAX_VALUE
        while (page <= maxPages && out.size < total) {
            val json = get(
                base.newBuilder().addPathSegments("observations/species_counts")
                    .addQueryParameter("place_id", placeId.toString())
                    .addQueryParameter("iconic_taxa", "Aves,Plantae")
                    .addQueryParameter("quality_grade", "research")
                    .addQueryParameter("locale", "en")
                    .addQueryParameter("preferred_place_id", placeId.toString())
                    .addQueryParameter("per_page", "500")
                    .addQueryParameter("page", page.toString())
                    .build(),
            )
            total = json.optInt("total_results", 0)
            val results = json.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val t = r.optJSONObject("taxon") ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
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
            onProgress(out.size, minOf(total, maxPages * 500))
            page++
            delay(1_100) // stay well under iNaturalist's ~1 request/second guidance
        }
        out
    }

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
