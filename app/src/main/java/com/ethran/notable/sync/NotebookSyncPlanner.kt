package com.ethran.notable.sync

/**
 * Remote manifest facts needed to reconcile one notebook: its `updatedAt` (epoch millis, or null if
 * the manifest carried none) and its current ETag.
 */
data class RemoteManifestInfo(val updatedAt: Long?, val etag: String?)

/**
 * The decision for a single notebook. Pure data — the executor turns it into WebDAV calls.
 */
sealed interface NotebookAction {
    /** Push local up. [ifMatch] guards the manifest PUT against a concurrent remote change. */
    data class Upload(val ifMatch: String?) : NotebookAction

    /** Pull remote down. */
    data object Download : NotebookAction

    /** Both sides already agree — nothing to transfer (the sync-state row is just refreshed). */
    data object Skip : NotebookAction

    /** Remote is newer but we are in upload-only mode, so the download is intentionally skipped. */
    data object SkipUploadOnly : NotebookAction
}

/**
 * The pure reconciliation decision for one *remote-present* notebook. No I/O: given the local
 * timestamp, what we last committed for it, and the remote facts (already fetched by the executor,
 * conditionally via `If-None-Match`), decide upload / download / skip.
 *
 * The "remote absent" case (a notebook that exists locally but not on the server) is handled by the
 * executor directly as a plain upload — it needs no timestamp reasoning.
 *
 * Conflict handling: when *both* sides changed, this returns the last-writer-wins outcome (upload if
 * local is newer, download if remote is newer). Surfacing a conflict badge is deferred to Phase 7.
 */
object NotebookSyncPlanner {
    const val TOLERANCE_MS = 1000L

    fun decide(
        /** Local `Notebook.updatedAt`, epoch millis. */
        localUpdatedAt: Long,
        /** `localUpdatedAtAtSync` from the sync-state row, or null if never synced. */
        syncedLocalUpdatedAt: Long?,
        /** ETag we stored for the remote manifest at the last sync (used as `If-Match` on upload). */
        storedEtag: String?,
        /**
         * Whether the remote manifest changed since [storedEtag]. `false` means the conditional GET
         * returned 304 (remote is exactly what we last synced), so [remote] is null.
         */
        remoteChanged: Boolean,
        /** The freshly fetched remote manifest facts; non-null iff [remoteChanged] is true. */
        remote: RemoteManifestInfo?,
        uploadOnly: Boolean,
        toleranceMs: Long = TOLERANCE_MS,
    ): NotebookAction {
        if (!remoteChanged) {
            // Remote == our last committed sync. Decide purely on whether local moved since then.
            val changedLocally =
                syncedLocalUpdatedAt == null || localUpdatedAt - syncedLocalUpdatedAt > toleranceMs
            return if (changedLocally) NotebookAction.Upload(storedEtag) else NotebookAction.Skip
        }

        // Remote changed since our last sync (or we had no stored ETag and did a full GET).
        val r = remote ?: return NotebookAction.Upload(storedEtag)
        val remoteUpdatedAt = r.updatedAt ?: return NotebookAction.Upload(r.etag)
        val diff = localUpdatedAt - remoteUpdatedAt
        return when {
            diff > toleranceMs -> NotebookAction.Upload(r.etag)      // local newer
            diff < -toleranceMs ->                                    // remote newer
                if (uploadOnly) NotebookAction.SkipUploadOnly else NotebookAction.Download

            else -> NotebookAction.Skip                              // within tolerance -> equal
        }
    }
}
