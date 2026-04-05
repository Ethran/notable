package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.R
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import dagger.hilt.android.EntryPointAccessors
import io.shipbook.shipbooksdk.Log

/**
 * Background worker for periodic WebDAV synchronization.
 * Runs via WorkManager on a periodic schedule (minimum 15 minutes per WorkManager constraints).
 */
class SyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "SyncWorker started")

        // Check connectivity first
        val connectivityChecker = ConnectivityChecker(applicationContext)
        if (!connectivityChecker.isNetworkAvailable()) {
            Log.i(TAG, "No network available, will retry later")
            return Result.retry()
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncOrchestratorEntryPoint::class.java
        )

        val credentialManager = entryPoint.credentialManager()
        val syncSettings = credentialManager.settings.value

        // Check if sync is enabled
        if (!syncSettings.syncEnabled) {
            Log.i(TAG, "Sync disabled in settings, skipping")
            return Result.success()
        }

        // Check WiFi-only constraint
        if (syncSettings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            Log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success()
        }

        // Check if we have credentials
        if (!credentialManager.hasCredentials()) {
            Log.w(TAG, "No credentials stored, skipping sync")
            return Result.success()
        }

        val syncType = inputData.getString("sync_type") ?: "syncAll"
        val syncTrigger = inputData.getString("sync_trigger")
        val isPeriodicSync = syncTrigger == SYNC_TRIGGER_PERIODIC

        // Perform sync based on type
        return try {
            if (isPeriodicSync) {
                showSyncSnack(R.string.sync_scheduled_started)
            }

            val result = when (syncType) {
                "syncAll" -> entryPoint.syncOrchestrator().syncAllNotebooks()
                "forceUpload" -> entryPoint.syncOrchestrator().forceUploadAll()
                "forceDownload" -> entryPoint.syncOrchestrator().forceDownloadAll()
                "uploadDeletion" -> {
                    val notebookId = inputData.getString("notebookId") ?: return Result.failure()
                    entryPoint.syncOrchestrator().uploadDeletion(notebookId)
                }

                "syncNotebook" -> {
                    val notebookId = inputData.getString("notebookId") ?: return Result.failure()
                    entryPoint.syncOrchestrator().syncNotebook(notebookId)
                }

                "syncFromPageId" -> {
                    val pageId = inputData.getString("pageId") ?: return Result.failure()
                    entryPoint.syncOrchestrator().syncFromPageId(pageId)
                    SyncResult.Success // syncFromPageId doesn't return a result, so we wrap it
                }

                else -> SyncResult.Failure(SyncError.UNKNOWN_ERROR)
            }
            when (result) {
                is SyncResult.Success -> {
                    Log.i(TAG, "Sync $syncType completed successfully")
                    Result.success(workDataOf("success" to true))
                }

                is SyncResult.Failure -> {
                    val errorStr = result.error.name
                    when (result.error) {
                        SyncError.SYNC_IN_PROGRESS -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            // Don't retry - another sync is already running
                            Result.success(
                                workDataOf(
                                    "success" to false,
                                    "error" to errorStr
                                )
                            )
                        }

                        SyncError.NETWORK_ERROR -> {
                            Log.e(TAG, "Network error during sync")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(
                                    workDataOf(
                                        "success" to false,
                                        "error" to errorStr
                                    )
                                )
                            }
                        }

                        SyncError.AUTH_ERROR,
                        SyncError.CONFIG_ERROR,
                        SyncError.CLOCK_SKEW,
                        SyncError.WIFI_REQUIRED,
                        SyncError.CONFLICT -> {
                            Log.w(TAG, "Sync skipped (non-retryable): ${result.error}")
                            Result.success(
                                workDataOf(
                                    "success" to false,
                                    "error" to errorStr
                                )
                            )
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: ${result.error}")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(
                                    workDataOf(
                                        "success" to false,
                                        "error" to errorStr
                                    )
                                )
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
                        "success" to false,
                        "error" to "UNKNOWN_EXCEPTION"
                    )
                )
            }
        } finally {
            if (isPeriodicSync)
                showSyncSnack(R.string.sync_scheduled_completed)
             else
                showSyncSnack(R.string.sync_completed_successfully)
        }
    }

    private suspend fun showSyncSnack(textResId: Int) {
        SnackState.globalSnackFlow.emit(
            SnackConf(
                text = applicationContext.getString(textResId),
                duration = 3000
            )
        )
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SYNC_TRIGGER_PERIODIC = "periodic"

        /**
         * Unique work name for periodic sync.
         */
        const val WORK_NAME = "notable-periodic-sync"
    }
}
