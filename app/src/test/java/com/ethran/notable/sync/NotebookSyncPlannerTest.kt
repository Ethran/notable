package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven tests for the pure reconciliation decision. Timestamps are in millis; the tolerance
 * is [NotebookSyncPlanner.TOLERANCE_MS] (1000).
 */
class NotebookSyncPlannerTest {

    private fun decide(
        localUpdatedAt: Long,
        syncedLocalUpdatedAt: Long?,
        storedEtag: String?,
        remoteChanged: Boolean,
        remote: RemoteManifestInfo?,
        uploadOnly: Boolean = false,
    ): NotebookAction = NotebookSyncPlanner.decide(
        localUpdatedAt = localUpdatedAt,
        syncedLocalUpdatedAt = syncedLocalUpdatedAt,
        storedEtag = storedEtag,
        remoteChanged = remoteChanged,
        remote = remote,
        uploadOnly = uploadOnly,
    )

    // ---- remote unchanged (304) ----

    @Test
    fun remoteUnchanged_localUnchanged_skips() {
        val action = decide(
            localUpdatedAt = 5_000,
            syncedLocalUpdatedAt = 5_000,
            storedEtag = "etag-1",
            remoteChanged = false,
            remote = null,
        )
        assertEquals(NotebookAction.Skip, action)
    }

    @Test
    fun remoteUnchanged_localEdited_uploadsWithStoredEtag() {
        val action = decide(
            localUpdatedAt = 9_000,
            syncedLocalUpdatedAt = 5_000,
            storedEtag = "etag-1",
            remoteChanged = false,
            remote = null,
        )
        assertEquals(NotebookAction.Upload("etag-1"), action)
    }

    @Test
    fun remoteUnchanged_localWithinTolerance_skips() {
        val action = decide(
            localUpdatedAt = 5_800,
            syncedLocalUpdatedAt = 5_000, // +800ms, within tolerance
            storedEtag = "etag-1",
            remoteChanged = false,
            remote = null,
        )
        assertEquals(NotebookAction.Skip, action)
    }

    @Test
    fun remoteUnchanged_neverSyncedLocally_uploads() {
        // No prior anchor -> treat as a local change to be pushed.
        val action = decide(
            localUpdatedAt = 5_000,
            syncedLocalUpdatedAt = null,
            storedEtag = "etag-1",
            remoteChanged = false,
            remote = null,
        )
        assertEquals(NotebookAction.Upload("etag-1"), action)
    }

    // ---- remote changed ----

    @Test
    fun remoteChanged_localNewer_uploadsWithFreshEtag() {
        val action = decide(
            localUpdatedAt = 10_000,
            syncedLocalUpdatedAt = 4_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 6_000, etag = "fresh"),
        )
        assertEquals(NotebookAction.Upload("fresh"), action)
    }

    @Test
    fun remoteChanged_remoteNewer_downloads() {
        val action = decide(
            localUpdatedAt = 6_000,
            syncedLocalUpdatedAt = 6_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 10_000, etag = "fresh"),
        )
        assertEquals(NotebookAction.Download, action)
    }

    @Test
    fun remoteChanged_remoteNewer_uploadOnly_skipsUploadOnly() {
        val action = decide(
            localUpdatedAt = 6_000,
            syncedLocalUpdatedAt = 6_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 10_000, etag = "fresh"),
            uploadOnly = true,
        )
        assertEquals(NotebookAction.SkipUploadOnly, action)
    }

    @Test
    fun remoteChanged_withinTolerance_skips() {
        val action = decide(
            localUpdatedAt = 10_500,
            syncedLocalUpdatedAt = 4_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 10_000, etag = "fresh"), // +500ms
        )
        assertEquals(NotebookAction.Skip, action)
    }

    @Test
    fun remoteChanged_bothEdited_localNewer_lastWriterWinsUpload() {
        // Both sides moved since last sync; local is newer -> LWW picks upload.
        val action = decide(
            localUpdatedAt = 20_000,
            syncedLocalUpdatedAt = 5_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 12_000, etag = "fresh"),
        )
        assertEquals(NotebookAction.Upload("fresh"), action)
    }

    @Test
    fun remoteChanged_noStoredEtag_fullGet_remoteNewer_downloads() {
        // First sync of an existing remote notebook: no stored etag, full GET, remote wins.
        val action = decide(
            localUpdatedAt = 3_000,
            syncedLocalUpdatedAt = null,
            storedEtag = null,
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = 9_000, etag = "server"),
        )
        assertEquals(NotebookAction.Download, action)
    }

    @Test
    fun remoteChanged_remoteHasNoTimestamp_uploads() {
        val action = decide(
            localUpdatedAt = 3_000,
            syncedLocalUpdatedAt = 3_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = RemoteManifestInfo(updatedAt = null, etag = "fresh"),
        )
        assertEquals(NotebookAction.Upload("fresh"), action)
    }

    @Test
    fun remoteChanged_flaggedButManifestMissing_uploadsDefensively() {
        val action = decide(
            localUpdatedAt = 3_000,
            syncedLocalUpdatedAt = 3_000,
            storedEtag = "old",
            remoteChanged = true,
            remote = null,
        )
        assertEquals(NotebookAction.Upload("old"), action)
    }
}
