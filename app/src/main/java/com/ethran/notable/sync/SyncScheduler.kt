package com.ethran.notable.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Helper to schedule/unschedule background sync with WorkManager.
 */
object SyncScheduler {

    /**
     * Enable periodic background sync.
     * @param context Android context
     * @param intervalMinutes Sync interval in minutes (default 5)
     */
    fun enablePeriodicSync(context: Context, intervalMinutes: Long = 5) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatInterval = intervalMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,  // Keep existing if already scheduled
            syncRequest
        )
    }

    /**
     * Disable periodic background sync.
     * @param context Android context
     */
    fun disablePeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }

    /**
     * Trigger an immediate sync (one-time work).
     * @param context Android context
     */
    fun triggerImmediateSync(context: Context) {
        // TODO: Implement one-time sync work request
        // For now, just trigger through SyncEngine directly
    }
}
