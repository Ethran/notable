package com.ethran.notable.sync

import android.content.Context
import androidx.work.Constraints
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
                SyncRequest.SyncAll.toDataBuilder()
                    .putString(SyncWorker.KEY_SYNC_TRIGGER, SyncWorker.SYNC_TRIGGER_PERIODIC)
                    .build()
            )
            .setConstraints(constraints)
            .addTag(SyncWorker.SYNC_WORK_TAG)
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
        request: SyncRequest = SyncRequest.SyncAll
    ): UUID {
        val builder = request.toDataBuilder()
            .putString(SyncWorker.KEY_SYNC_TRIGGER, SyncWorker.SYNC_TRIGGER_IMMEDIATE)

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(builder.build())
            .addTag(SyncWorker.SYNC_WORK_TAG)
            .build()

        val uniqueName = "${SyncWorker.WORK_NAME}-immediate-${request.typeKey}-${request.identifier}"

        // KEEP, not REPLACE: a sync already running for this unique name satisfies the request.
        // REPLACE would cancel an in-flight worker mid-sync (e.g. app restarted during a sync). (P20)
        workManager.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            syncWorkRequest
        )

        return syncWorkRequest.id
    }
}
