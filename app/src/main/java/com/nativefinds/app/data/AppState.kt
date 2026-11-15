package com.nativefinds.app.data

import android.content.Context

/** Small bits of app state that aren't user settings: last backup, last place the app was used, alerts sent. */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0L)
        set(v) = prefs.edit().putLong("last_backup_at", v).apply()

    var lastBackupMessage: String?
        get() = prefs.getString("last_backup_message", null)
        set(v) = prefs.edit().putString("last_backup_message", v).apply()

    /** PKCE verifier while the user is signing in to Dropbox in the browser. */
    var dropboxVerifier: String?
        get() = prefs.getString("dropbox_verifier", null)
        set(v) = prefs.edit().putString("dropbox_verifier", v).apply()

    /** Where the app last got a location fix, used as an alert centre (no background location needed). */
    var lastLocation: Pair<Double, Double>?
        get() {
            if (!prefs.contains("last_lat")) return null
            return prefs.getFloat("last_lat", 0f).toDouble() to prefs.getFloat("last_lng", 0f).toDouble()
        }
        set(v) {
            if (v == null) prefs.edit().remove("last_lat").remove("last_lng").apply()
            else prefs.edit().putFloat("last_lat", v.first.toFloat()).putFloat("last_lng", v.second.toFloat()).apply()
        }

    /** Species already alerted, with when, so each is only mentioned once a month. */
    fun alertedAt(name: String): Long = prefs.getLong("alerted:" + name.lowercase(), 0L)
    fun markAlerted(name: String, at: Long) = prefs.edit().putLong("alerted:" + name.lowercase(), at).apply()
}
