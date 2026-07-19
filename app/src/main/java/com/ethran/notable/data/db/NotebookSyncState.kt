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
}

class NotebookSyncStateRepository @Inject constructor(
    private val dao: NotebookSyncStateDao
) {
    suspend fun get(id: String): NotebookSyncState? = dao.get(id)
    suspend fun getAllIds(): Set<String> = dao.getAllIds().toSet()
    fun getAllFlow(): Flow<List<NotebookSyncState>> = dao.getAllFlow()
    suspend fun upsert(state: NotebookSyncState) = dao.upsert(state)
    suspend fun delete(id: String) = dao.delete(id)
}
