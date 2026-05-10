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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper to schedule/unschedule background sync with WorkManager.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    // WorkManager enforces a minimum interval of 15 minutes for periodic work.
    private val minPeriodicSyncIntervalMinutes = 15L

    /**
     * Reconcile periodic sync schedule against persisted sync settings.
     */
    fun reconcilePeriodicSync(settings: SyncSettings) {
        if (settings.syncEnabled && settings.autoSync) {
            enablePeriodicSync(
                intervalMinutes = settings.syncInterval.toLong(),
                wifiOnly = settings.wifiOnly
            )
            return
        }
        disablePeriodicSync()
    }

    private fun enablePeriodicSync(
        intervalMinutes: Long = minPeriodicSyncIntervalMinutes,
        wifiOnly: Boolean = false
    ) {
        val safeIntervalMinutes = intervalMinutes.coerceAtLeast(minPeriodicSyncIntervalMinutes)

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

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    private fun disablePeriodicSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME)
    }

    fun triggerImmediateSync(
        syncType: String = "syncAll",
        data: Map<String, String> = emptyMap()
    ): UUID {
        val builder = Data.Builder()
            .putString(INPUT_KEY_SYNC_TYPE, syncType)
            .putString(INPUT_KEY_SYNC_TRIGGER, SYNC_TRIGGER_IMMEDIATE)

        data.forEach { (k, v) -> builder.putString(k, v) }

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(builder.build())
            .build()

        val workSuffix = data.entries
            .sortedBy { it.key }
            .joinToString(separator = "-") { "${it.key}:${it.value}" }
            .ifEmpty { "default" }

        workManager.enqueueUniqueWork(
            "${SyncWorker.WORK_NAME}-immediate-$syncType-$workSuffix",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )

        return syncRequest.id
    }

    companion object {
        private const val INPUT_KEY_SYNC_TYPE = "sync_type"
        private const val INPUT_KEY_SYNC_TRIGGER = "sync_trigger"
        private const val DEFAULT_SYNC_TYPE = "syncAll"
        private const val SYNC_TRIGGER_PERIODIC = "periodic"
        private const val SYNC_TRIGGER_IMMEDIATE = "immediate"
    }
}