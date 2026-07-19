package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.SyncStateValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Per-notebook sync badge shown in the library. */
enum class SyncBadge { NOT_SYNCED, SYNCING, SYNCED, ERROR }

/**
 * Derives a per-notebook [SyncBadge] map from the notebook list, the `notebook_sync_state` table,
 * and the live sync state. Nothing is stored: the badge is a pure function of those three sources,
 * so it stays correct as notebooks are edited, synced, or fail.
 */
@Singleton
class NotebookSyncStatusStore @Inject constructor(
    appRepository: AppRepository,
    reporter: SyncProgressReporter,
) {
    val badges: Flow<Map<String, SyncBadge>> = combine(
        appRepository.bookRepository.getAllFlow(),
        appRepository.notebookSyncStateRepository.getAllFlow(),
        reporter.state,
    ) { notebooks, states, syncState ->
        val syncing = syncState is SyncState.Syncing
        val byId = states.associateBy { it.notebookId }
        notebooks.associate { nb ->
            val row = byId[nb.id]
            val base = when {
                row == null -> SyncBadge.NOT_SYNCED
                row.state == SyncStateValue.ERROR -> SyncBadge.ERROR
                // Local edited since its last committed sync -> pending upload.
                nb.updatedAt.time - row.localUpdatedAtAtSync.time > TOLERANCE_MS -> SyncBadge.NOT_SYNCED
                else -> SyncBadge.SYNCED
            }
            // While a sync is running, anything not already synced reads as in-progress.
            nb.id to if (syncing && base != SyncBadge.SYNCED) SyncBadge.SYNCING else base
        }
    }

    companion object {
        private const val TOLERANCE_MS = 1000L
    }
}
