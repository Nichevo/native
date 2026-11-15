package com.nativefinds.app

import android.app.Application
import com.nativefinds.app.data.AppDatabase
import com.nativefinds.app.data.LocationProvider
import com.nativefinds.app.data.PhotoStore
import com.nativefinds.app.data.SettingsRepository
import com.nativefinds.app.id.Identifier
import com.nativefinds.app.id.OllamaClient
import com.nativefinds.app.id.OnDeviceClassifier
import com.nativefinds.app.id.PlantNetClient
import com.nativefinds.app.info.INatClient
import com.nativefinds.app.info.RegionPackRepository
import com.nativefinds.app.info.SpeciesRepository
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val appContext: android.content.Context = app
    val db = AppDatabase.create(app)
    val settings = SettingsRepository(app)
    val photos = PhotoStore(app)
    val location = LocationProvider(app)
    val inat = INatClient()
    val onDevice = OnDeviceClassifier(app)
    val ollama = OllamaClient()
    val regions = RegionPackRepository(db, inat)
    val species = SpeciesRepository(db, inat)
    val speciesDetails = com.nativefinds.app.info.SpeciesDetailsRepository(db)
    val pests = com.nativefinds.app.info.PestList(app)
    val birdSong = com.nativefinds.app.audio.BirdSongClassifier(app)
    val state = com.nativefinds.app.data.AppState(app)
    val backup = com.nativefinds.app.backup.BackupManager(app, this)
    val identifier = Identifier(PlantNetClient(), onDevice, ollama, regions, species, birdSong)
}

class NativeFindsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.nativefinds.app.id.PcQueueWorker.schedule(this)
        com.nativefinds.app.alerts.AlertNotifier.createChannel(this)
        kotlinx.coroutines.MainScope().launch {
            val s = container.settings.current()
            com.nativefinds.app.backup.BackupManager.schedule(this@NativeFindsApp, s.dropboxConnected && s.autoBackup)
            com.nativefinds.app.alerts.SightingAlertWorker.schedule(this@NativeFindsApp, s.alertsEnabled)
        }
        Configuration.getInstance().apply {
            load(this@NativeFindsApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "NativeFinds/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
    }
}
