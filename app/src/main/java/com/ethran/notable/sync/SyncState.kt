package com.ethran.notable.sync

import com.ethran.notable.utils.DomainError

/**
 * Observable state of the sync engine, owned by [SyncProgressReporter] and rendered by the sync UI.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(
        val currentStep: SyncStep,
        val stepProgress: Float,
        val details: String,
        val item: ItemProgress? = null
    ) : SyncState()

    data class Success(val summary: SyncSummary) : SyncState()
    data class Error(val error: DomainError, val step: SyncStep, val canRetry: Boolean) :
        SyncState()
}

data class ItemProgress(val index: Int, val total: Int, val name: String)

enum class SyncStep {
    INITIALIZING, SYNCING_FOLDERS, APPLYING_DELETIONS, SYNCING_NOTEBOOKS,
    DOWNLOADING_NEW, UPLOADING_DELETIONS, FINALIZING
}

data class SyncSummary(
    val notebooksSynced: Int,
    val notebooksDownloaded: Int,
    val notebooksDeleted: Int,
    val duration: Long
)
