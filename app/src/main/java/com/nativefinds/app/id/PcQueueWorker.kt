package com.nativefinds.app.id

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nativefinds.app.NativeFindsApp
import com.nativefinds.app.ui.common.formatDate
import java.util.concurrent.TimeUnit

/**
 * Works through finds waiting for the PC AI. Runs when the phone has a connection; if the PC is still
 * unreachable it tries again later. The PC's answer is added to the find's notes, and becomes the
 * species only if the find was saved unidentified.
 */
class PcQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NativeFindsApp).container
        val s = c.settings.current()
        if (!s.pcAiConfigured || s.offlineMode) return Result.success()
        for (f in c.db.finds().pendingPc()) {
            val photos = c.db.finds().photos(f.id).map { Identifier.Photo(it.path, categoryOf(it.category)) }
                .ifEmpty { listOf(Identifier.Photo(f.photoPath, categoryOf(f.category))) }
            val opinion = try {
                c.identifier.secondOpinion(photos, s)
            } catch (e: PcUnreachableException) {
                return Result.retry()
            } catch (e: Exception) {
                c.db.finds().update(
                    f.copy(pendingPc = false, notes = appendNote(f.notes, "PC AI couldn't check this find: ${e.message}")),
                )
                continue
            }
            val top = opinion.candidates.firstOrNull()
            val summary = buildString {
                append("PC AI (").append(formatDate(System.currentTimeMillis())).append("): ")
                if (top == null) {
                    append("no confident identification")
                } else {
                    append(top.commonName ?: top.scientificName)
                    if (top.commonName != null) append(" (").append(top.scientificName).append(")")
                    append(", ").append((top.score * 100).toInt()).append("%")
                }
                opinion.notes?.let { append(". ").append(it) }
            }
            val updated = if (f.scientificName == null && top != null) {
                f.copy(
                    scientificName = top.scientificName,
                    commonName = top.commonName,
                    confidence = top.score,
                    idSource = IdSource.PC_AI.label,
                    establishment = top.establishment,
                    pendingPc = false,
                    notes = appendNote(f.notes, summary),
                )
            } else {
                f.copy(pendingPc = false, notes = appendNote(f.notes, summary))
            }
            c.db.finds().update(updated)
        }
        return Result.success()
    }

    private fun categoryOf(name: String) = runCatching { Category.valueOf(name) }.getOrDefault(Category.OTHER)

    private fun appendNote(notes: String, add: String) = if (notes.isBlank()) add else "$notes\n\n$add"

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Hourly background check, so queued finds get answered once the PC is back. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "pc-queue-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PcQueueWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        /** Try right away (e.g. just after queueing a find). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pc-queue-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PcQueueWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
