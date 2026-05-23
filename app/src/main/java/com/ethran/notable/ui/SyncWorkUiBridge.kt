package com.ethran.notable.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ethran.notable.R
import com.ethran.notable.sync.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncWorkUiBridge @Inject constructor(
    @ApplicationContext context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val handledIds = LinkedHashSet<String>()

    // Flag to track the very first time the Flow reads from the DB
    private var isInitialDbRead = true

    val syncUiEvents: Flow<SnackEvent> = workManager
        .getWorkInfosByTagFlow(SyncWorker.SYNC_WORK_TAG)
        .transform { infos ->
            val finishedWorks = infos.filter { it.state.isFinished }

            // On app launch, populate our handledIds with past jobs but DO NOT emit Snackbars
            if (isInitialDbRead) {
                finishedWorks.forEach { info ->
                    handledIds.add("${info.id}:${info.state.name}")
                }
                isInitialDbRead = false
                return@transform
            }

            // For all subsequent emissions, check if it's a new finish state
            finishedWorks.forEach { info ->
                val key = "${info.id}:${info.state.name}"
                if (handledIds.add(key)) {
                    trimHandledIds()
                    emit(buildSnackEvent(info))
                }
            }
        }

    private fun trimHandledIds(maxSize: Int = 64) {
        if (handledIds.size <= maxSize) return
        val iterator = handledIds.iterator()
        while (handledIds.size > maxSize && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun buildSnackEvent(info: WorkInfo): SnackEvent {
        val output = info.outputData
        val success = output.getBoolean(SyncWorker.OUTPUT_KEY_SUCCESS, false)
        val skipped = output.getBoolean(SyncWorker.OUTPUT_KEY_SKIPPED, false)
        val errorMsg = output.getString(SyncWorker.OUTPUT_KEY_ERROR)

        return when {
            skipped -> SnackEvent(R.string.sync_skipped)
            success -> SnackEvent(R.string.sync_completed_successfully)
            info.state == WorkInfo.State.CANCELLED -> SnackEvent(R.string.sync_cancelled)
            errorMsg != null -> SnackEvent(R.string.sync_failed_message, errorMsg)
            else -> SnackEvent(R.string.sync_finished)
        }
    }
}

/**
 * A simple data class to carry resource IDs to the UI layer safely.
 */
data class SnackEvent(
    @param:StringRes val messageResId: Int,
    val errorArg: String? = null
)