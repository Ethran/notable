package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.SyncStateValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Per-notebook sync badge shown in the library. */
enum class SyncBadge {
    /** Local changes not yet synced (no sync running). */
    NOT_SYNCED,

    /** A sync is running and this notebook is queued but not yet its turn. */
    SCHEDULED,

    /** This notebook is being uploaded/downloaded right now. */
    SYNCING,

    /** Local matches the last committed sync. */
    SYNCED,

    /** Server has a newer version that was not pulled (upload-only mode). */
    REMOTE_AHEAD,

    /** The last sync of this notebook failed. */
    ERROR,
}

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
        // The one notebook the engine is processing right now (null between items).
        val currentId = (syncState as? SyncState.Syncing)?.item?.id
        val byId = states.associateBy { it.notebookId }
        notebooks.associate { nb ->
            val row = byId[nb.id]
            val base = when {
                row == null -> SyncBadge.NOT_SYNCED
                row.state == SyncStateValue.ERROR -> SyncBadge.ERROR
                // Local edited since its last committed sync -> pending upload (supersedes a stale
                // REMOTE_AHEAD: once you edit locally, the badge is about your unsynced change).
                nb.updatedAt.time - row.localUpdatedAtAtSync.time > TOLERANCE_MS -> SyncBadge.NOT_SYNCED
                row.state == SyncStateValue.REMOTE_AHEAD -> SyncBadge.REMOTE_AHEAD
                else -> SyncBadge.SYNCED
            }
            val badge = when {
                base == SyncBadge.SYNCED -> SyncBadge.SYNCED
                // Exactly the notebook being transferred right now spins.
                syncing && currentId == nb.id -> SyncBadge.SYNCING
                // Other pending notebooks during a run are queued, not "syncing".
                syncing -> SyncBadge.SCHEDULED
                else -> base
            }
            nb.id to badge
        }
    }

    companion object {
        private const val TOLERANCE_MS = 1000L
    }
}
