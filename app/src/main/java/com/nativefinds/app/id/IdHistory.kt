package com.nativefinds.app.id

import com.nativefinds.app.data.IdRun
import org.json.JSONArray
import org.json.JSONObject

/** One photo's own matches, before photos are combined. */
data class PhotoResult(val photo: String, val candidates: List<Candidate>)

/** Turns identification results into [IdRun] rows and back. */
object IdHistory {
    const val AUTO = "AUTO"
    const val PC = "PC"
    const val SONG = "SONG"
    const val CHOICE = "CHOICE"

    fun run(
        findId: Long,
        at: Long,
        kind: String,
        label: String,
        method: String?,
        candidates: List<Candidate>,
        perPhoto: List<PhotoResult> = emptyList(),
        chosen: String? = null,
    ) = IdRun(
        findId = findId, at = at, kind = kind, label = label, method = method,
        candidatesJson = encode(candidates).toString(),
        perPhotoJson = if (perPhoto.isEmpty()) null else JSONArray().apply {
            perPhoto.forEach { p -> put(JSONObject().put("photo", p.photo).put("candidates", encode(p.candidates.take(5)))) }
        }.toString(),
        chosen = chosen,
    )

    fun candidates(run: IdRun): List<Candidate> = decode(runCatching { JSONArray(run.candidatesJson) }.getOrDefault(JSONArray()))

    fun perPhoto(run: IdRun): List<PhotoResult> {
        val arr = run.perPhotoJson?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PhotoResult(o.optString("photo"), decode(o.optJSONArray("candidates") ?: JSONArray()))
        }
    }

    private fun encode(list: List<Candidate>) = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject()
                    .put("name", c.scientificName)
                    .put("common", c.commonName ?: JSONObject.NULL)
                    .put("score", c.score.toDouble())
                    .put("source", c.source.name)
                    .put("region", c.regionMatch.name)
                    .put("note", c.note ?: JSONObject.NULL)
                    .put("establishment", c.establishment ?: JSONObject.NULL),
            )
        }
    }

    private fun decode(arr: JSONArray): List<Candidate> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Candidate(
            scientificName = o.optString("name").ifBlank { return@mapNotNull null },
            commonName = o.optString("common").takeIf { !o.isNull("common") && it.isNotBlank() },
            score = o.optDouble("score", 0.0).toFloat(),
            source = runCatching { IdSource.valueOf(o.optString("source")) }.getOrDefault(IdSource.MANUAL),
            regionMatch = runCatching { RegionMatch.valueOf(o.optString("region")) }.getOrDefault(RegionMatch.UNKNOWN),
            note = o.optString("note").takeIf { !o.isNull("note") && it.isNotBlank() },
            establishment = o.optString("establishment").takeIf { !o.isNull("establishment") && it.isNotBlank() },
        )
    }
}
