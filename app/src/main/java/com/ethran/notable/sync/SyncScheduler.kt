package com.ethran.notable.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Helper to schedule/unschedule background sync with WorkManager.
 */
object SyncScheduler {

    // WorkManager enforces a minimum interval of 15 minutes for periodic work.
    private const val MIN_PERIODIC_SYNC_INTERVAL_MINUTES = 15L

    /**
     * Reconcile periodic sync schedule against persisted sync settings.
     */
    fun reconcilePeriodicSync(
        context: Context,
        settings: SyncSettings
    ) {
        if (settings.syncEnabled && settings.autoSync) {
            enablePeriodicSync(
                context = context,
                intervalMinutes = settings.syncInterval.toLong(),
                wifiOnly = settings.wifiOnly
            )
            return
        }
        disablePeriodicSync(context)
    }

    /**
     * Enable periodic background sync.
     * @param context Android context
     * @param intervalMinutes Sync interval in minutes
     * @param wifiOnly If true, only run on unmetered (WiFi) connections
     */
    fun enablePeriodicSync(
        context: Context,
        intervalMinutes: Long = MIN_PERIODIC_SYNC_INTERVAL_MINUTES,
        wifiOnly: Boolean = false
    ) {
        val safeIntervalMinutes = intervalMinutes.coerceAtLeast(MIN_PERIODIC_SYNC_INTERVAL_MINUTES)

        // UNMETERED covers WiFi and ethernet but excludes metered mobile connections.
        // This matches the intent of the "WiFi only" setting (avoid burning mobile data).
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatInterval = safeIntervalMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setInputData(
                Data.Builder()
                    .putString(INPUT_KEY_SYNC_TYPE, DEFAULT_SYNC_TYPE)
                    .putString(INPUT_KEY_SYNC_TRIGGER, SYNC_TRIGGER_PERIODIC)
                    .build()
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
     * @param syncType The specific sync action ("syncAll", "forceUpload", "forceDownload", etc.)
     * @param data Optional extra data (like notebookId)
     */
    fun triggerImmediateSync(
        context: Context,
        syncType: String = "syncAll",
        data: Map<String, String> = emptyMap()
    ): UUID {
        val builder = Data.Builder()
            .putString(INPUT_KEY_SYNC_TYPE, syncType)
            .putString(INPUT_KEY_SYNC_TRIGGER, SYNC_TRIGGER_IMMEDIATE)
        for ((k, v) in data) {
            builder.putString(k, v)
        }
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(builder.build())
            .build()
        val workSuffix = data.entries
            .sortedBy { it.key }
            .joinToString(separator = "-") { "${it.key}:${it.value}" }
            .ifEmpty { "default" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            /* uniqueWorkName = */ "${SyncWorker.WORK_NAME}-immediate-$syncType-$workSuffix",
            /* existingWorkPolicy = */ ExistingWorkPolicy.REPLACE,
            /* work = */ syncRequest
        )
        return syncRequest.id
    }

    private const val INPUT_KEY_SYNC_TYPE = "sync_type"
    private const val INPUT_KEY_SYNC_TRIGGER = "sync_trigger"
    private const val DEFAULT_SYNC_TYPE = "syncAll"
    private const val SYNC_TRIGGER_PERIODIC = "periodic"
    private const val SYNC_TRIGGER_IMMEDIATE = "immediate"
}
