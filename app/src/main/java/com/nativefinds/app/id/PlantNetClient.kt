package com.nativefinds.app.id

import com.nativefinds.app.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Pl@ntNet identification API (https://my.plantnet.org). Free accounts get 500 identifications a day.
 */
class PlantNetClient {
    /** Identifies from up to 5 photos of the same plant at once; more organs = more confident results. */
    suspend fun identify(photos: List<Pair<String, String>>, apiKey: String, max: Int = 5): List<Candidate> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IdentificationException("Add your Pl@ntNet API key in Settings")
            val url = "https://my-api.plantnet.org/v2/identify/all".toHttpUrl().newBuilder()
                .addQueryParameter("api-key", apiKey)
                .addQueryParameter("lang", "en")
                .addQueryParameter("nb-results", max.toString())
                .addQueryParameter("include-related-images", "false")
                .build()
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            photos.take(5).forEachIndexed { i, (path, organ) ->
                val jpeg = PhotoStore.jpegBytes(path, maxSide = 1280)
                form.addFormDataPart("images", "photo$i.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                form.addFormDataPart("organs", organ)
            }
            val body = form.build()
            val req = Request.Builder().url(url).post(body).build()
            Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 -> return@withContext emptyList()
                    resp.code == 401 || resp.code == 403 ->
                        throw IdentificationException("Pl@ntNet rejected the API key (${resp.code})")
                    resp.code == 429 -> throw IdentificationException("Pl@ntNet daily limit reached")
                    !resp.isSuccessful -> throw IdentificationException("Pl@ntNet error ${resp.code}")
                }
                parse(text)
            }
        }

    private fun parse(json: String): List<Candidate> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val species = r.optJSONObject("species") ?: return@mapNotNull null
            val name = species.optString("scientificNameWithoutAuthor").ifBlank { return@mapNotNull null }
            val common = species.optJSONArray("commonNames")?.let { if (it.length() > 0) it.optString(0) else null }
            val family = species.optJSONObject("family")?.optString("scientificNameWithoutAuthor")
            Candidate(
                scientificName = name,
                commonName = common?.takeIf { it.isNotBlank() },
                score = r.optDouble("score", 0.0).toFloat(),
                source = IdSource.PLANTNET,
                family = family?.takeIf { it.isNotBlank() },
            )
        }
    }
}
