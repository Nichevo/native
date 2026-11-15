package com.nativefinds.app.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nativefinds.app.AppContainer
import com.nativefinds.app.NativeFindsApp
import com.nativefinds.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Backs up the finds database and every photo and sound to the user's Dropbox app folder, and
 * restores them onto a new phone. Photos are only uploaded once; the database is replaced each time,
 * with one dated copy kept per day.
 */
class BackupManager(private val context: Context, private val c: AppContainer) {
    private val photosDir = File(context.filesDir, "photos")
    private val dbFile get() = context.getDatabasePath(AppDatabase.NAME)

    data class Report(val photosUploaded: Int, val photosTotal: Int)

    suspend fun backup(onProgress: (Int, Int) -> Unit = { _, _ -> }): Report = withContext(Dispatchers.IO) {
        val s = c.settings.current()
        if (!s.dropboxConnected) error("Dropbox isn't set up yet")
        val token = Dropbox.accessToken(s.dropboxAppKey, s.dropboxRefreshToken)

        // A consistent copy of the database.
        c.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val snap = File(context.cacheDir, "backup/${AppDatabase.NAME}").apply { parentFile?.mkdirs() }
        dbFile.copyTo(snap, overwrite = true)
        Dropbox.upload(token, snap, "/${AppDatabase.NAME}", overwrite = true)
        Dropbox.upload(token, snap, "/history/finds-${LocalDate.now()}.db", overwrite = true)
        snap.delete()

        val have = Dropbox.listNames(token, "/photos")
        val files = photosDir.listFiles().orEmpty().filter { it.isFile }
        val todo = files.filter { it.name !in have }
        todo.forEachIndexed { i, f ->
            onProgress(i, todo.size)
            Dropbox.upload(token, f, "/photos/${f.name}", overwrite = false)
        }
        onProgress(todo.size, todo.size)
        c.state.lastBackupAt = System.currentTimeMillis()
        c.state.lastBackupMessage = null
        Report(todo.size, files.size)
    }

    /**
     * Replaces this phone's finds with the Dropbox backup, downloads any missing photos, then
     * restarts the app. Settings on this phone (keys, region) are kept.
     */
    suspend fun restore(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val s = c.settings.current()
        if (!s.dropboxConnected) error("Dropbox isn't set up yet")
        val token = Dropbox.accessToken(s.dropboxAppKey, s.dropboxRefreshToken)
        onProgress("Downloading your finds…")
        val tmp = File(context.cacheDir, "restore/${AppDatabase.NAME}").apply { parentFile?.mkdirs() }
        try {
            Dropbox.download(token, "/${AppDatabase.NAME}", tmp)
        } catch (e: Dropbox.NotFound) {
            error("There's no backup in this Dropbox yet")
        }
        val header = tmp.inputStream().use { ByteArray(16).also { b -> it.read(b) } }
        if (!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) error("The backup file looks damaged")

        val names = Dropbox.listNames(token, "/photos")
        photosDir.mkdirs()
        val missing = names.filter { !File(photosDir, it).exists() }
        missing.forEachIndexed { i, n ->
            onProgress("Downloading photos and sounds ${i + 1} of ${missing.size}…")
            Dropbox.download(token, "/photos/$n", File(photosDir, n))
        }

        onProgress("Putting it all in place…")
        c.db.close()
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        tmp.copyTo(dbFile, overwrite = true)
        tmp.delete()
        fixPaths()
        restartApp()
    }

    /** Photo paths in the backup point at the old phone's folder; point them at this one. */
    private fun fixPaths() {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val dir = photosDir.path
            for ((table, col) in listOf("finds" to "photoPath", "find_photos" to "path")) {
                val rows = mutableListOf<Pair<Long, String>>()
                runCatching {
                    db.rawQuery("SELECT rowid, $col FROM $table", null).use { cur ->
                        while (cur.moveToNext()) rows += cur.getLong(0) to cur.getString(1)
                    }
                }
                for ((id, path) in rows) {
                    val fixed = "$dir/${File(path).name}"
                    if (fixed != path) db.execSQL("UPDATE $table SET $col = ? WHERE rowid = ?", arrayOf<Any>(fixed, id))
                }
            }
        } finally {
            db.close()
        }
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork("dropbox-daily"); return }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            wm.enqueueUniquePeriodicWork(
                "dropbox-daily",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build(),
            )
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NativeFindsApp).container
        val s = c.settings.current()
        if (!s.dropboxConnected || !s.autoBackup || s.offlineMode) return Result.success()
        return runCatching { c.backup.backup() }.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                c.state.lastBackupMessage = "Last automatic backup failed: ${e.message}"
                if (runAttemptCount < 3) Result.retry() else Result.success()
            },
        )
    }
}
