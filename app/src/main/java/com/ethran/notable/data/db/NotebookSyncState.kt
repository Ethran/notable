package com.ethran.notable.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject

/**
 * Per-notebook sync bookkeeping, written only at commit points (a successful upload or download).
 *
 * Deliberately has **no** foreign key to [Notebook]: the row must outlive local deletion of the
 * notebook so the next sync can still detect "was synced, now gone" and propagate a tombstone. It
 * is the single source of truth for badges, deletion detection, and (Phase 5) change detection —
 * the richer replacement for the old `SyncSettings.syncedNotebookIds` set.
 */
@Entity(tableName = "notebook_sync_state")
data class NotebookSyncState(
    @PrimaryKey val notebookId: String,
    /** [SyncStateValue.SYNCED] or [SyncStateValue.ERROR]. */
    val state: String,
    val lastSyncedAt: Date,
    /** The local `Notebook.updatedAt` at the moment this notebook last committed a sync. */
    val localUpdatedAtAtSync: Date,
    val remoteEtag: String? = null,
    val remoteUpdatedAt: Date? = null,
    val lastError: String? = null,
)

object SyncStateValue {
    const val SYNCED = "SYNCED"
    const val ERROR = "ERROR"

    /** Remote is newer than local, but it was not pulled (upload-only mode). */
    const val REMOTE_AHEAD = "REMOTE_AHEAD"
}

@Dao
interface NotebookSyncStateDao {
    @Query("SELECT * FROM notebook_sync_state WHERE notebookId = :id")
    suspend fun get(id: String): NotebookSyncState?

    @Query("SELECT notebookId FROM notebook_sync_state")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM notebook_sync_state")
    fun getAllFlow(): Flow<List<NotebookSyncState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: NotebookSyncState)

    @Query("DELETE FROM notebook_sync_state WHERE notebookId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notebook_sync_state")
    suspend fun deleteAll()
}

class NotebookSyncStateRepository @Inject constructor(
    private val dao: NotebookSyncStateDao
) {
    suspend fun get(id: String): NotebookSyncState? = dao.get(id)
    suspend fun getAllIds(): Set<String> = dao.getAllIds().toSet()
    fun getAllFlow(): Flow<List<NotebookSyncState>> = dao.getAllFlow()
    suspend fun upsert(state: NotebookSyncState) = dao.upsert(state)
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()

    /** Record a notebook as successfully synced at [localUpdatedAt] / [remoteUpdatedAt]. */
    suspend fun markSynced(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        remoteEtag: String?,
    ) = dao.upsert(
        NotebookSyncState(
            notebookId = notebookId,
            state = SyncStateValue.SYNCED,
            lastSyncedAt = Date(),
            localUpdatedAtAtSync = localUpdatedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            remoteEtag = remoteEtag,
        )
    )

    /**
     * Record that the server copy is newer than local but was intentionally not pulled (upload-only
     * mode). Anchored at the current [localUpdatedAt] so a later local edit flips the badge to
     * "pending upload" rather than staying "remote ahead".
     */
    suspend fun markRemoteAhead(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        remoteEtag: String?,
    ) = dao.upsert(
        NotebookSyncState(
            notebookId = notebookId,
            state = SyncStateValue.REMOTE_AHEAD,
            lastSyncedAt = Date(),
            localUpdatedAtAtSync = localUpdatedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            remoteEtag = remoteEtag,
        )
    )

    /**
     * Record that a notebook's last sync attempt failed, so the library shows the ERROR badge.
     * Preserves the previous sync anchor ([localUpdatedAtAtSync], ETag, remote timestamp) if a row
     * already existed, so a later successful sync still knows what was last committed. If no row
     * existed, an epoch-0 anchor is used — the ERROR state is checked before the anchor, so the
     * badge is correct either way.
     */
    suspend fun markError(notebookId: String, message: String?) {
        val existing = dao.get(notebookId)
        dao.upsert(
            NotebookSyncState(
                notebookId = notebookId,
                state = SyncStateValue.ERROR,
                lastSyncedAt = Date(),
                localUpdatedAtAtSync = existing?.localUpdatedAtAtSync ?: Date(0),
                remoteUpdatedAt = existing?.remoteUpdatedAt,
                remoteEtag = existing?.remoteEtag,
                lastError = message,
            )
        )
    }
}
