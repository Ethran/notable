package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        // Perform sync
        return try {
            when (val result = entryPoint.syncOrchestrator().syncAllNotebooks()) {
                is SyncResult.Success -> {
                    Log.i(TAG, "Sync completed successfully")
                    Result.success()
                }

                is SyncResult.Failure -> {
                    when (result.error) {
                        SyncError.SYNC_IN_PROGRESS -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            // Don't retry - another sync is already running
                            Result.success()
                        }

                        SyncError.NETWORK_ERROR -> {
                            Log.e(TAG, "Network error during sync")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure()
                            }
                        }

                        SyncError.AUTH_ERROR,
                        SyncError.CONFIG_ERROR,
                        SyncError.CLOCK_SKEW,
                        SyncError.WIFI_REQUIRED,
                        SyncError.CONFLICT -> {
                            Log.w(TAG, "Sync skipped (non-retryable): ${result.error}")
                            Result.success()
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: ${result.error}")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure()
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
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * Unique work name for periodic sync.
         */
        const val WORK_NAME = "notable-periodic-sync"
    }
}
