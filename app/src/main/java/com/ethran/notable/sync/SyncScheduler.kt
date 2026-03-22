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

    // WorkManager enforces a minimum interval of 15 minutes for periodic work.
    private const val DEFAULT_SYNC_INTERVAL_MINUTES = 15L

    /**
     * Enable periodic background sync.
     * @param context Android context
     * @param intervalMinutes Sync interval in minutes
     * @param wifiOnly If true, only run on unmetered (WiFi) connections
     */
    fun enablePeriodicSync(
        context: Context,
        intervalMinutes: Long = DEFAULT_SYNC_INTERVAL_MINUTES,
        wifiOnly: Boolean = false
    ) {
        // UNMETERED covers WiFi and ethernet but excludes metered mobile connections.
        // This matches the intent of the "WiFi only" setting (avoid burning mobile data).
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatInterval = intervalMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,  // Update constraints if already scheduled
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
