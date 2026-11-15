package com.nativefinds.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nativefinds.app.AppContainer
import com.nativefinds.app.NativeFindsApp
import com.nativefinds.app.R
import com.nativefinds.app.data.RegionSpecies
import java.util.concurrent.TimeUnit

/** One unusual species seen near one of your spots. */
data class RareSighting(val scientificName: String, val name: String, val distanceKm: Double, val near: String, val observedOn: String?)

/**
 * Finds unusual species seen recently near your places (and where you last used the app) that you
 * haven't found yet. "Unusual" leaves out the everyday ones: the most-recorded 30% of each group
 * (birds, plants, bugs…) in your region pack, introduced species and pests.
 */
class SightingAlerts(private val c: AppContainer) {
    suspend fun check(days: Int = 7): List<RareSighting> {
        val s = c.settings.current()
        val placeId = s.regionPlaceId
        val pack = c.db.regions().species(placeId)
        if (pack.isEmpty()) return emptyList()
        val byName = pack.associateBy { speciesKey(it.scientificName) }
        val everyday = everydaySet(pack)
        val found = c.db.finds().getAll().mapNotNull { it.scientificName?.let(::speciesKey) }.toSet()

        val centres = buildList<Triple<String, Double, Double>> {
            c.db.places().all().forEach { add(Triple(it.name, it.latitude, it.longitude)) }
            c.state.lastLocation?.let { (lat, lng) ->
                val nearAPlace = any { (_, pl, pg) -> distanceKm(lat, lng, pl, pg) < s.alertRadiusKm / 2.0 }
                if (!nearAPlace) add(Triple("where you last used the app", lat, lng))
            }
        }
        val best = mutableMapOf<String, RareSighting>()
        for ((label, lat, lng) in centres) {
            val sightings = runCatching { c.inat.recentSightings(lat, lng, s.alertRadiusKm, days, placeId) }.getOrNull() ?: continue
            for (o in sightings) {
                val key = speciesKey(o.scientificName)
                if (!key.contains(' ') || key in found || key in everyday) continue
                val inPack = byName[key]
                if (inPack?.establishment == "introduced") continue
                if (c.pests.lookup(o.scientificName, placeId) != null) continue
                val d = distanceKm(lat, lng, o.latitude, o.longitude)
                val prev = best[key]
                if (prev == null || d < prev.distanceKm) {
                    best[key] = RareSighting(
                        scientificName = o.scientificName,
                        name = o.commonName ?: inPack?.commonName ?: o.scientificName,
                        distanceKm = d,
                        near = label,
                        observedOn = o.observedOn,
                    )
                }
            }
        }
        return best.values.sortedBy { it.distanceKm }
    }

    private fun everydaySet(pack: List<RegionSpecies>): Set<String> =
        pack.groupBy { it.iconicTaxon ?: "" }.values.flatMap { group ->
            val sorted = group.sortedByDescending { it.observations }
            sorted.take((sorted.size * 0.3).toInt().coerceAtLeast(1))
        }.map { speciesKey(it.scientificName) }.toSet()

    companion object {
        fun speciesKey(name: String): String = name.trim().lowercase().split(Regex("\\s+")).take(2).joinToString(" ")

        fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, out)
            return out[0] / 1000.0
        }
    }
}

object AlertNotifier {
    const val CHANNEL = "rare-sightings"
    const val EXTRA_ROUTE = "open_route"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Unusual species nearby", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Uncommon species seen near your places that you haven't found yet"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, list: List<RareSighting>) {
        if (list.isEmpty() || !canNotify(context)) return
        val first = list.first()
        val title = if (list.size == 1) "${first.name} seen nearby" else "${list.size} unusual species seen nearby"
        val text = if (list.size == 1) {
            "%.1f km from %s%s. You haven't found one yet.".format(first.distanceKm, first.near, first.observedOn?.let { " on $it" } ?: "")
        } else {
            list.take(6).joinToString { it.name } + if (list.size > 6) "…" else ""
        }
        val route = if (list.size == 1) "species/" + android.net.Uri.encode(first.scientificName) else "nearby"
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                list.take(10).joinToString("\n") { "${it.name}: %.1f km from %s".format(it.distanceKm, it.near) },
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(4242, n)
        } catch (_: SecurityException) {
        }
    }
}

class SightingAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NativeFindsApp).container
        val s = c.settings.current()
        if (!s.alertsEnabled || s.offlineMode) return Result.success()
        val now = System.currentTimeMillis()
        val fresh = runCatching { SightingAlerts(c).check() }.getOrElse { return Result.retry() }
            .filter { now - c.state.alertedAt(it.scientificName) > 30L * 86_400_000L }
        if (fresh.isNotEmpty()) {
            AlertNotifier.notify(applicationContext, fresh)
            fresh.forEach { c.state.markAlerted(it.scientificName, now) }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork("rare-sightings"); return }
            wm.enqueueUniquePeriodicWork(
                "rare-sightings",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SightingAlertWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
