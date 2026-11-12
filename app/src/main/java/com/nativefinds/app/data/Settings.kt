package com.nativefinds.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val plantNetApiKey: String = "",
    val regionPlaceId: Int = Regions.NEW_ZEALAND.placeId,
    val regionName: String = Regions.NEW_ZEALAND.name,
    /** Only use on-phone models and cached data; no internet calls. */
    val offlineMode: Boolean = false,
    /** Base URL of the Ollama server on the PC, e.g. https://ai.example.com */
    val pcAiUrl: String = "",
    val pcAiModel: String = "qwen2.5vl:32b",
    /** Cloudflare Access service token (optional). */
    val cfClientId: String = "",
    val cfClientSecret: String = "",
) {
    val pcAiConfigured: Boolean get() = pcAiUrl.isNotBlank() && pcAiModel.isNotBlank()
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val plantNetKey = stringPreferencesKey("plantnet_key")
        val regionPlaceId = intPreferencesKey("region_place_id")
        val regionName = stringPreferencesKey("region_name")
        val offline = booleanPreferencesKey("offline_mode")
        val pcUrl = stringPreferencesKey("pc_ai_url")
        val pcModel = stringPreferencesKey("pc_ai_model")
        val cfId = stringPreferencesKey("cf_client_id")
        val cfSecret = stringPreferencesKey("cf_client_secret")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.plantNetKey] = s.plantNetApiKey.trim()
            prefs[Keys.regionPlaceId] = s.regionPlaceId
            prefs[Keys.regionName] = s.regionName
            prefs[Keys.offline] = s.offlineMode
            prefs[Keys.pcUrl] = s.pcAiUrl.trim().trimEnd('/')
            prefs[Keys.pcModel] = s.pcAiModel.trim()
            prefs[Keys.cfId] = s.cfClientId.trim()
            prefs[Keys.cfSecret] = s.cfClientSecret.trim()
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            plantNetApiKey = this[Keys.plantNetKey] ?: d.plantNetApiKey,
            regionPlaceId = this[Keys.regionPlaceId] ?: d.regionPlaceId,
            regionName = this[Keys.regionName] ?: d.regionName,
            offlineMode = this[Keys.offline] ?: d.offlineMode,
            pcAiUrl = this[Keys.pcUrl] ?: d.pcAiUrl,
            pcAiModel = this[Keys.pcModel] ?: d.pcAiModel,
            cfClientId = this[Keys.cfId] ?: d.cfClientId,
            cfClientSecret = this[Keys.cfSecret] ?: d.cfClientSecret,
        )
    }
}
