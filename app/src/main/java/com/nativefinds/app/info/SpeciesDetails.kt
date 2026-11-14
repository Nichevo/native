package com.nativefinds.app.info

import com.nativefinds.app.data.AppDatabase
import com.nativefinds.app.data.SpeciesDetailsRow
import com.nativefinds.app.id.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Everything we show on a species page beyond the basic card. */
data class SpeciesDetails(
    /** Article sections from Wikipedia: Description, Distribution and habitat, Behaviour, Diet... */
    val sections: List<Section>,
    val photos: List<Photo>,
    /** Kingdom → species, as (rank, name, common name). */
    val taxonomy: List<Rank>,
    /** Research-grade iNaturalist records in the region. */
    val recordsInRegion: Int?,
    val recordsWorldwide: Int?,
    val similar: List<Similar>,
    val wikipediaTitle: String?,
) {
    data class Section(val title: String, val text: String)
    data class Photo(val url: String, val attribution: String?)
    data class Rank(val rank: String, val name: String, val common: String?)
    data class Similar(val scientificName: String, val commonName: String?, val photoUrl: String?, val confusions: Int)
}

/**
 * Builds and caches species pages from iNaturalist (photos, taxonomy, record counts, commonly
 * confused species) and Wikipedia (the full article, split into sections).
 */
class SpeciesDetailsRepository(private val db: AppDatabase) {
    private val inat = "https://api.inaturalist.org/v1".toHttpUrl()
    private val maxAgeMs = 30L * 24 * 3600 * 1000

    suspend fun details(scientificName: String, taxonId: Long?, placeId: Int, offline: Boolean): SpeciesDetails? {
        val cached = db.speciesInfo().getDetails(scientificName, placeId)
        val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAgeMs
        if (offline || fresh) return cached?.let { decode(it.json) }
        val built = runCatching { fetch(scientificName, taxonId, placeId) }.getOrNull()
        if (built != null) {
            db.speciesInfo().putDetails(SpeciesDetailsRow(scientificName, placeId, encode(built), System.currentTimeMillis()))
            return built
        }
        return cached?.let { decode(it.json) }
    }

    private suspend fun fetch(name: String, knownTaxonId: Long?, placeId: Int): SpeciesDetails = coroutineScope {
        val taxonId = knownTaxonId ?: withContext(Dispatchers.IO) {
            get(inat.newBuilder().addPathSegment("taxa").addQueryParameter("q", name).addQueryParameter("per_page", "5").build())
                .optJSONArray("results")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").equals(name, true) } ?: arr.optJSONObject(0)
                }?.optLong("id")
        }
        val taxon = async(Dispatchers.IO) {
            taxonId?.let {
                runCatching {
                    get(
                        inat.newBuilder().addPathSegment("taxa").addPathSegment(it.toString())
                            .addQueryParameter("locale", "en").addQueryParameter("preferred_place_id", placeId.toString()).build(),
                    ).optJSONArray("results")?.optJSONObject(0)
                }.getOrNull()
            }
        }
        val regionCount = async(Dispatchers.IO) {
            taxonId?.let {
                runCatching {
                    get(
                        inat.newBuilder().addPathSegment("observations")
                            .addQueryParameter("taxon_id", it.toString()).addQueryParameter("place_id", placeId.toString())
                            .addQueryParameter("quality_grade", "research").addQueryParameter("per_page", "0").build(),
                    ).optInt("total_results")
                }.getOrNull()
            }
        }
        val similar = async(Dispatchers.IO) {
            taxonId?.let {
                runCatching {
                    val arr = get(
                        inat.newBuilder().addPathSegments("identifications/similar_species")
                            .addQueryParameter("taxon_id", it.toString()).addQueryParameter("place_id", placeId.toString())
                            .addQueryParameter("locale", "en").addQueryParameter("preferred_place_id", placeId.toString()).build(),
                    ).optJSONArray("results") ?: JSONArray()
                    (0 until minOf(arr.length(), 8)).mapNotNull { i ->
                        val r = arr.getJSONObject(i)
                        val t = r.optJSONObject("taxon") ?: return@mapNotNull null
                        SpeciesDetails.Similar(
                            t.optString("name"),
                            t.optString("preferred_common_name").ifBlank { null },
                            t.optJSONObject("default_photo")?.optString("square_url")?.ifBlank { null },
                            r.optInt("count"),
                        )
                    }
                }.getOrNull()
            }.orEmpty()
        }
        val t = taxon.await()
        val wikiTitle = t?.optString("wikipedia_url")?.substringAfterLast("/wiki/", "")?.ifBlank { null }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: name
        val sections = withContext(Dispatchers.IO) { runCatching { wikipediaSections(wikiTitle) }.getOrDefault(emptyList()) }

        val photos = t?.optJSONArray("taxon_photos")?.let { arr ->
            (0 until minOf(arr.length(), 10)).mapNotNull { i ->
                val p = arr.optJSONObject(i)?.optJSONObject("photo") ?: return@mapNotNull null
                val url = p.optString("medium_url").ifBlank { p.optString("url").replace("square", "medium") }.ifBlank { null }
                    ?: return@mapNotNull null
                SpeciesDetails.Photo(url, p.optString("attribution").ifBlank { null })
            }
        }.orEmpty()
        val taxonomy = t?.optJSONArray("ancestors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val rank = a.optString("rank")
                if (rank !in setOf("kingdom", "phylum", "class", "order", "family", "genus")) return@mapNotNull null
                SpeciesDetails.Rank(rank, a.optString("name"), a.optString("preferred_common_name").ifBlank { null })
            }
        }.orEmpty()

        SpeciesDetails(
            sections = sections,
            photos = photos,
            taxonomy = taxonomy,
            recordsInRegion = regionCount.await(),
            recordsWorldwide = t?.optInt("observations_count")?.takeIf { it > 0 },
            similar = similar.await().filterNot { it.scientificName.equals(name, true) },
            wikipediaTitle = if (sections.isEmpty()) null else wikiTitle,
        )
    }

    /** The whole Wikipedia article as plain text, split at its "== Heading ==" lines. */
    private fun wikipediaSections(title: String): List<SpeciesDetails.Section> {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("prop", "extracts")
            .addQueryParameter("explaintext", "1").addQueryParameter("exsectionformat", "wiki")
            .addQueryParameter("redirects", "1").addQueryParameter("format", "json")
            .addQueryParameter("titles", title).build()
        val pages = get(url).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return emptyList()
        val text = page.optString("extract").ifBlank { return emptyList() }

        val skip = setOf(
            "references", "external links", "see also", "further reading", "notes", "gallery", "bibliography",
            "citations", "sources", "footnotes", "explanatory notes", "cited texts", "works cited",
        )
        val out = mutableListOf<SpeciesDetails.Section>()
        var title2 = "Overview"
        val buf = StringBuilder()
        var skipping = false
        fun flush() {
            val body = buf.toString().trim().replace(Regex("\n{3,}"), "\n\n")
            if (!skipping && body.length > 40) {
                // Sub-sections (=== x ===) are folded into their parent section.
                val last = out.lastOrNull()
                out += SpeciesDetails.Section(title2, body)
                if (last != null && last.title == title2) out.removeAt(out.size - 2)
            }
            buf.clear()
        }
        val heading = Regex("^(={2,})\\s*(.+?)\\s*\\1\\s*$")
        for (line in text.lines()) {
            val m = heading.matchEntire(line)
            if (m == null) { buf.append(line).append('\n'); continue }
            val level = m.groupValues[1].length
            val name = m.groupValues[2]
            if (level == 2) {
                flush()
                title2 = name
                skipping = name.lowercase() in skip
            } else {
                // Keep sub-headings inline, in the same section.
                buf.append('\n').append(name).append('\n')
            }
        }
        flush()
        return out
    }

    private fun get(url: HttpUrl): JSONObject {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "NativeFinds (Android nature ID app)")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun encode(d: SpeciesDetails): String = JSONObject().apply {
        put("sections", JSONArray(d.sections.map { JSONObject().put("t", it.title).put("x", it.text) }))
        put("photos", JSONArray(d.photos.map { JSONObject().put("u", it.url).put("a", it.attribution ?: "") }))
        put("taxonomy", JSONArray(d.taxonomy.map { JSONObject().put("r", it.rank).put("n", it.name).put("c", it.common ?: "") }))
        put("region", d.recordsInRegion ?: -1)
        put("world", d.recordsWorldwide ?: -1)
        put("similar", JSONArray(d.similar.map {
            JSONObject().put("n", it.scientificName).put("c", it.commonName ?: "").put("p", it.photoUrl ?: "").put("k", it.confusions)
        }))
        put("wiki", d.wikipediaTitle ?: "")
    }.toString()

    private fun decode(s: String): SpeciesDetails? = runCatching {
        val o = JSONObject(s)
        fun JSONObject.str(k: String) = optString(k).ifBlank { null }
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        SpeciesDetails(
            sections = arr("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { j -> SpeciesDetails.Section(j.optString("t"), j.optString("x")) } } },
            photos = arr("photos").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { j -> SpeciesDetails.Photo(j.optString("u"), j.str("a")) } } },
            taxonomy = arr("taxonomy").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { j -> SpeciesDetails.Rank(j.optString("r"), j.optString("n"), j.str("c")) } } },
            recordsInRegion = o.optInt("region", -1).takeIf { it >= 0 },
            recordsWorldwide = o.optInt("world", -1).takeIf { it >= 0 },
            similar = arr("similar").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { j -> SpeciesDetails.Similar(j.optString("n"), j.str("c"), j.str("p"), j.optInt("k")) } }
            },
            wikipediaTitle = o.str("wiki"),
        )
    }.getOrNull()
}
