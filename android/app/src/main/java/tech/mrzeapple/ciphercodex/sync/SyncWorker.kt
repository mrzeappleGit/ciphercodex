package tech.mrzeapple.ciphercodex.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.mrzeapple.ciphercodex.CipherCodexApp
import java.util.concurrent.TimeUnit

/** Periodic, connectivity-gated push of any progress that never synced from the
 *  reader (e.g. a book finished offline and never reopened). No-op when sync is
 *  unconfigured; retries on failure so a transient outage is not lost. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CipherCodexApp
        return try {
            val summary = app.syncManager.syncAllDirty()
            if (summary.failed > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "kosync-periodic"

        /** Enqueue the periodic sync (idempotent — KEEP an existing schedule). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
