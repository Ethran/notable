package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import dagger.hilt.android.EntryPointAccessors
import io.shipbook.shipbooksdk.Log

/**
 * Background worker for WebDAV synchronization.
 * Runs via WorkManager. Emits success/error data via WorkManager Results.
 */
class SyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "SyncWorker started")

        // 1. Dynamic Checks
        val connectivityChecker = ConnectivityChecker(applicationContext)
        if (!connectivityChecker.isNetworkAvailable()) {
            Log.i(TAG, "No network available, will retry later")
            return Result.retry()
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncOrchestratorEntryPoint::class.java
        )

        val kvProxy = entryPoint.kvProxy()
        val syncSettings = kvProxy.getSyncSettings()

        if (!syncSettings.syncEnabled) {
            Log.i(TAG, "Sync disabled in settings, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            Log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.username.isBlank() || syncSettings.password.isBlank()) {
            Log.w(TAG, "No credentials stored, skipping sync")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        // 2. Parse Input
        val syncRequest = SyncRequest.fromData(inputData)
            ?: return Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to "INVALID_INPUT"))

        val syncTrigger = inputData.getString(KEY_SYNC_TRIGGER) ?: SYNC_TRIGGER_PERIODIC
        val isPeriodicSync = syncTrigger == SYNC_TRIGGER_PERIODIC

        // 3. Execute Sync
        return try {
            val result = when (syncRequest) {
                SyncRequest.SyncAll -> entryPoint.syncOrchestrator().syncAllNotebooks()
                SyncRequest.ForceUpload -> entryPoint.syncOrchestrator().forceUploadAll()
                SyncRequest.ForceDownload -> entryPoint.syncOrchestrator().forceDownloadAll()
                is SyncRequest.UploadDeletion -> entryPoint.syncOrchestrator().uploadDeletion(syncRequest.notebookId)
                is SyncRequest.SyncNotebook -> entryPoint.syncOrchestrator().syncNotebook(syncRequest.notebookId)
                is SyncRequest.SyncFromPageId -> {
                    entryPoint.syncOrchestrator().syncFromPageId(syncRequest.pageId)
                    AppResult.Success(Unit)
                }
            }

            // 4. Handle Results
            when (result) {
                is AppResult.Success -> {
                    Log.i(TAG, "Sync $syncRequest completed successfully")
                    Result.success(
                        workDataOf(
                            OUTPUT_KEY_SUCCESS to true,
                            OUTPUT_KEY_IS_PERIODIC to isPeriodicSync
                        )
                    )
                }

                is AppResult.Error -> {
                    val error = result.error
                    val errorStr = error.javaClass.simpleName
                    val failureMessage = error.userMessage

                    when (error) {
                        is DomainError.SyncInProgress -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            // Returning success so it doesn't log as a strict failure, but marking success as false
                            Result.success(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to errorStr))
                        }

                        is DomainError.NetworkError -> {
                            Log.e(TAG, "Network error during sync: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }

                        is DomainError.SyncAuthError,
                        is DomainError.SyncConfigError,
                        is DomainError.SyncClockSkew,
                        is DomainError.SyncWifiRequired,
                        is DomainError.SyncConflict -> {
                            Log.w(TAG, "Sync failed (non-retryable): $failureMessage")
                            // These are hard failures, mark them as such
                            Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS && error.recoverable) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in SyncWorker: ${e.message}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        OUTPUT_KEY_SUCCESS to false,
                        OUTPUT_KEY_ERROR to (e.localizedMessage ?: "UNKNOWN_EXCEPTION")
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3

        const val KEY_SYNC_TRIGGER = "sync_trigger"
        const val SYNC_TRIGGER_PERIODIC = "periodic"
        const val SYNC_TRIGGER_IMMEDIATE = "immediate"

        // Output keys for the UI to observe
        const val OUTPUT_KEY_SUCCESS = "success"
        const val OUTPUT_KEY_ERROR = "error"
        const val OUTPUT_KEY_IS_PERIODIC = "is_periodic"
        const val OUTPUT_KEY_SKIPPED = "skipped"

        const val SYNC_WORK_TAG = "sync-work"

        /**
         * Unique work name for periodic sync.
         */
        const val WORK_NAME = "notable-periodic-sync"
    }
}