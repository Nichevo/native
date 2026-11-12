package com.nativefinds.app.id

import android.util.Base64
import com.nativefinds.app.data.AppSettings
import com.nativefinds.app.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Second opinion" from a vision model running in Ollama on your own PC, reached through a
 * Cloudflare Tunnel (or any URL). Optional Cloudflare Access service-token headers are sent if set.
 */
class OllamaClient {
    data class Opinion(val candidates: List<Candidate>, val notes: String?)

    suspend fun identify(photoPath: String, category: String, regionName: String, settings: AppSettings): Opinion =
        withContext(Dispatchers.IO) {
            if (!settings.pcAiConfigured) throw IdentificationException("Set up your PC AI in Settings")
            val b64 = Base64.encodeToString(PhotoStore.jpegBytes(photoPath, maxSide = 1024), Base64.NO_WRAP)
            val prompt = """
                You are an expert naturalist. Identify the organism in this photo.
                The person says it is: $category. It was photographed in or near: $regionName.
                Prefer species known to occur in $regionName unless the photo clearly shows otherwise.
                Reply with JSON only, in exactly this shape:
                {"candidates":[{"scientific_name":"Genus species","common_name":"name","confidence":0.0,"reason":"short visual reason"}],
                 "notes":"2-4 sentences about the most likely species: appearance, habitat, whether native to $regionName"}
                Give up to 3 candidates, most likely first. confidence is 0 to 1. If unsure, say so with low confidence.
            """.trimIndent()
            val payload = JSONObject()
                .put("model", settings.pcAiModel)
                .put("stream", false)
                .put("format", "json")
                .put("options", JSONObject().put("temperature", 0.2))
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt).put("images", JSONArray().put(b64)),
                    ),
                )
            val req = request(settings, "/api/chat")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.slowClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IdentificationException(describeError(resp.code, text))
                val content = JSONObject(text).optJSONObject("message")?.optString("content").orEmpty()
                parse(content)
            }
        }

    /** Lists models on the PC; used by the "Test connection" button. */
    suspend fun listModels(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        if (settings.pcAiUrl.isBlank()) throw IdentificationException("Enter the PC AI address first")
        Http.client.newCall(request(settings, "/api/tags").get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IdentificationException(describeError(resp.code, text))
            val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
            (0 until models.length()).map { models.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        }
    }

    private fun request(settings: AppSettings, path: String): Request.Builder {
        val base = settings.pcAiUrl.trim().trimEnd('/').let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        val b = Request.Builder().url(base + path)
        if (settings.cfClientId.isNotBlank()) b.header("CF-Access-Client-Id", settings.cfClientId)
        if (settings.cfClientSecret.isNotBlank()) b.header("CF-Access-Client-Secret", settings.cfClientSecret)
        return b
    }

    private fun describeError(code: Int, body: String): String = when (code) {
        401, 403 -> "The PC refused the request ($code). Check the Cloudflare Access client ID and secret."
        404 -> if (body.contains("model", ignoreCase = true)) "That model isn't installed on the PC" else "PC AI address not found (404)"
        502, 503, 530 -> "The PC isn't reachable right now ($code). Is it on, with Ollama and the tunnel running?"
        else -> "PC AI error $code"
    }

    private fun parse(content: String): Opinion {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return Opinion(emptyList(), content.takeIf { it.isNotBlank() })
        val obj = runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull()
            ?: return Opinion(emptyList(), content)
        val arr = obj.optJSONArray("candidates") ?: JSONArray()
        val candidates = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("scientific_name").trim().ifBlank { return@mapNotNull null }
            Candidate(
                scientificName = name,
                commonName = c.optString("common_name").trim().ifBlank { null },
                score = c.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                source = IdSource.PC_AI,
                note = c.optString("reason").trim().ifBlank { null },
            )
        }
        return Opinion(candidates, obj.optString("notes").trim().ifBlank { null })
    }
}
