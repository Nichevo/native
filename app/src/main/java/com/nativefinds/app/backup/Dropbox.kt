package com.nativefinds.app.backup

import android.net.Uri
import android.util.Base64
import com.nativefinds.app.id.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal Dropbox API client. Uses the user's own Dropbox app (App folder access) and the PKCE
 * "copy the code" sign-in, so no secret is built into the app.
 */
object Dropbox {
    private val octet = "application/octet-stream".toMediaType()
    private val json = "application/json".toMediaType()

    class NotFound : Exception("Not found in Dropbox")

    fun newVerifier(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun authorizeUrl(appKey: String, verifier: String): Uri {
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", appKey.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /** Swaps the code the user copied from Dropbox for a long-lived refresh token. */
    suspend fun finishSignIn(appKey: String, code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code", code.trim())
            .add("grant_type", "authorization_code")
            .add("client_id", appKey.trim())
            .add("code_verifier", verifier)
            .build()
        val o = call(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build())
        o.optString("refresh_token").ifBlank { error("Dropbox didn't return a sign-in token") }
    }

    /** A short-lived access token for this session's calls. */
    suspend fun accessToken(appKey: String, refreshToken: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", appKey.trim())
            .build()
        call(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build())
            .optString("access_token").ifBlank { error("Dropbox sign-in has expired. Sign in again in Settings.") }
    }

    /** Names of the files directly in [folder] (empty if the folder doesn't exist yet). */
    suspend fun listNames(token: String, folder: String): Set<String> = withContext(Dispatchers.IO) {
        val names = mutableSetOf<String>()
        var o = try {
            rpc(token, "files/list_folder", JSONObject().put("path", folder).put("limit", 2000))
        } catch (e: NotFound) {
            return@withContext names
        }
        while (true) {
            val entries = o.optJSONArray("entries")
            if (entries != null) for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.optString(".tag") == "file") names += e.optString("name")
            }
            if (!o.optBoolean("has_more")) break
            o = rpc(token, "files/list_folder/continue", JSONObject().put("cursor", o.optString("cursor")))
        }
        names
    }

    suspend fun upload(token: String, file: File, path: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        val arg = JSONObject()
            .put("path", path)
            .put("mode", if (overwrite) "overwrite" else "add")
            .put("autorename", false)
            .put("mute", true)
        val req = Request.Builder().url("https://content.dropboxapi.com/2/files/upload")
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", asciiJson(arg))
            .post(file.asRequestBody(octet))
            .build()
        Http.slowClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Dropbox upload failed (${resp.code}): ${resp.body?.string().orEmpty().take(200)}")
        }
    }

    suspend fun download(token: String, path: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://content.dropboxapi.com/2/files/download")
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", asciiJson(JSONObject().put("path", path)))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        Http.slowClient.newCall(req).execute().use { resp ->
            if (resp.code == 409) throw NotFound()
            if (!resp.isSuccessful) error("Dropbox download failed (${resp.code})")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
    }

    private fun rpc(token: String, endpoint: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url("https://api.dropboxapi.com/2/$endpoint")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 409 && text.contains("not_found")) throw NotFound()
            if (!resp.isSuccessful) error("Dropbox error ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun call(req: Request): JSONObject =
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error_description") }.getOrNull().orEmpty()
                error("Dropbox said: ${msg.ifBlank { "error ${resp.code}" }}")
            }
            JSONObject(text)
        }

    /** HTTP headers must be ASCII; Dropbox wants non-ASCII escaped as \\uXXXX. */
    private fun asciiJson(o: JSONObject): String = buildString {
        for (ch in o.toString()) if (ch.code < 128) append(ch) else append("\\u%04x".format(ch.code))
    }
}
