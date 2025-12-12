package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.shipbook.shipbooksdk.Log

/**
 * Background worker for periodic WebDAV synchronization.
 * Runs via WorkManager on a periodic schedule (e.g., every 5 minutes).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "SyncWorker started")

        // Check connectivity first
        val connectivityChecker = ConnectivityChecker(applicationContext)
        if (!connectivityChecker.isNetworkAvailable()) {
            Log.i(TAG, "No network available, will retry later")
            return Result.retry()
        }

        // Check if we have credentials
        val credentialManager = CredentialManager(applicationContext)
        if (!credentialManager.hasCredentials()) {
            Log.w(TAG, "No credentials stored, skipping sync")
            return Result.failure()
        }

        // Perform sync
        return try {
            val syncEngine = SyncEngine(applicationContext)
            val result = syncEngine.syncAllNotebooks()

            when (result) {
                is SyncResult.Success -> {
                    Log.i(TAG, "Sync completed successfully")
                    Result.success()
                }
                is SyncResult.Failure -> {
                    Log.e(TAG, "Sync failed: ${result.error}")
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
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
