package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.R
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
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

        val kvProxy = entryPoint.kvProxy()
        val syncSettings = kvProxy.getSyncSettings()

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
        if (syncSettings.username.isBlank() || syncSettings.encryptedPassword.isBlank()) {
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
                    AppResult.Success(Unit)
                }

                else -> AppResult.Error(DomainError.SyncError("Unknown sync type: $syncType"))
            }

            when (result) {
                is AppResult.Success -> {
                    Log.i(TAG, "Sync $syncType completed successfully")
                    Result.success(workDataOf("success" to true))
                }

                is AppResult.Error -> {
                    val error = result.error
                    val errorStr = error.javaClass.simpleName

                    when (error) {
                        is DomainError.SyncInProgress -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            Result.success(workDataOf("success" to false, "error" to errorStr))
                        }

                        is DomainError.NetworkError -> {
                            Log.e(TAG, "Network error during sync: ${error.userMessage}")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf("success" to false, "error" to errorStr))
                            }
                        }

                        is DomainError.SyncAuthError,
                        is DomainError.SyncConfigError,
                        is DomainError.SyncClockSkew,
                        is DomainError.SyncWifiRequired,
                        is DomainError.SyncConflict -> {
                            Log.w(TAG, "Sync skipped (non-retryable): ${error.userMessage}")
                            Result.success(workDataOf("success" to false, "error" to errorStr))
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: ${error.userMessage}")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS && error.recoverable) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf("success" to false, "error" to errorStr))
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

    private fun showSyncSnack(textResId: Int) {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncOrchestratorEntryPoint::class.java
        )
        entryPoint.snackDispatcher().showOrUpdateSnack(
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
