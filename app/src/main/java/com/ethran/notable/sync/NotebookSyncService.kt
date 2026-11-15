package com.ethran.notable.sync

import android.content.Context
import android.net.Uri
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.sync.PageSyncSelector.selectDirtyPages
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Below this many deletions the mass-deletion guard never trips, so ordinary single/few deletes
 * always propagate. See [looksLikeStaleStateWipe].
 */
internal const val MASS_DELETION_SAFETY_FLOOR = 10

/**
 * Whether deleting [deletionCount] notebooks out of a reference set of [referenceCount] is better
 * explained by lost local state than by an intentional bulk delete — at least
 * [MASS_DELETION_SAFETY_FLOOR] notebooks AND a strict majority of the reference set. Callers refuse
 * the deletion when this holds, so a device whose local rows were wiped while sync-state survived
 * cannot tombstone the whole server. Pure, so the guard is unit-testable independently of any I/O.
 *
 * [referenceCount] is what the deletions are measured against: all synced notebooks when detecting
 * local deletions, or the full server set when force upload prunes server-only notebooks.
 */
internal fun looksLikeStaleStateWipe(deletionCount: Int, referenceCount: Int): Boolean =
    deletionCount >= MASS_DELETION_SAFETY_FLOOR && deletionCount > referenceCount / 2

/**
 * Select remote notebooks that are absent locally and may be downloaded. A sync-state row suppresses
 * the download only when local deletion is authoritative; download-only devices are mirrors, so a
 * missing local notebook must be restored even when stale sync bookkeeping still exists for it.
 */
internal fun selectNewRemoteNotebookIds(
    remoteNotebookIds: Set<String>,
    localNotebookIds: Set<String>,
    tombstonedIds: Set<String>,
    syncedNotebookIds: Set<String>,
    downloadOnly: Boolean,
): List<String> = remoteNotebookIds
    .filter { it !in localNotebookIds }
    .filter { it !in tombstonedIds }
    .filter { downloadOnly || it !in syncedNotebookIds }

@Singleton
class NotebookSyncService @Inject constructor(
    private val appRepository: AppRepository,
    private val reporter: SyncProgressReporter,
    private val kvProxy: KvProxy,
    @param:ApplicationContext private val context: Context
) {
    private val log = SyncLogger

    suspend fun applyRemoteDeletions(
        client: WebDAVClient,
        maxAgeDays: Long
    ): AppResult<Set<String>, DomainError> {
        log.i(TAG, "Applying remote deletions...")
        val tombstonesPath = SyncPaths.tombstonesDir()

        val tombstonesExist = client.exists(tombstonesPath).onFailure { return AppResult.Error(it) }
        if (!tombstonesExist) return AppResult.Success(emptySet())

        return client.listCollectionWithMetadata(tombstonesPath).flatMap { tombstones ->
            val tombstonedIds = tombstones.map { it.name }.toSet()
            val errors = ErrorAccumulator()

            if (tombstones.isNotEmpty()) {
                log.i(TAG, "Server has ${tombstones.size} tombstone(s)")
                for (tombstone in tombstones) {
                    val notebookId = tombstone.name
                    val deletedAt = tombstone.lastModified
                    val localNotebook = appRepository.bookRepository.getById(notebookId) ?: continue

                    if (deletedAt != null) {
                        // Dated tombstone: keep the local copy if it was edited after the deletion.
                        if (localNotebook.updatedAt.after(deletedAt)) {
                            log.i(
                                TAG,
                                "↻ Resurrecting '${localNotebook.title}' (modified after server deletion)"
                            )
                            continue
                        }
                    } else if (hasUnsyncedLocalEdits(notebookId, localNotebook)) {
                        // Undated tombstone (server omitted Last-Modified): we cannot prove the local
                        // copy predates the deletion, so deleting it could drop work edited after
                        // another device removed the notebook. Only delete a clean copy; keep one with
                        // unsynced edits until a dated tombstone arrives or the user resolves it.
                        log.i(
                            TAG,
                            "↻ Keeping '${localNotebook.title}': undated tombstone but local has unsynced edits"
                        )
                        continue
                    }

                    log.i(
                        TAG,
                        "Deleting locally (tombstone on server): ${localNotebook.title}"
                    )
                    try {
                        appRepository.bookRepository.delete(notebookId)
                        // Gone on both sides now -- drop the sync-state row so it is not later
                        // mis-read as a local deletion to re-tombstone, and the per-page rows with
                        // it: a resurrected notebook must re-download every page, not skip pages
                        // whose stale ETag still matches while no local page row exists.
                        appRepository.notebookSyncStateRepository.delete(notebookId)
                        appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                    } catch (e: Exception) {
                        log.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                        errors.add(DomainError.DatabaseError("Failed to delete ${localNotebook.title}"))
                    }
                }
            }

            val cutoff = Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
            val stale =
                tombstones.filter { it.lastModified != null && it.lastModified.before(cutoff) }
            if (stale.isNotEmpty()) {
                log.i(TAG, "Pruning ${stale.size} stale tombstone(s) older than $maxAgeDays days")
                for (entry in stale) {
                    client.delete(SyncPaths.tombstone(entry.name)).onError {
                        log.w(TAG, "Failed to prune tombstone ${entry.name}: ${it.userMessage}")
                    }
                }
            }

            errors.asResult(tombstonedIds)
        }
    }

    /**
     * Whether [notebook] carries local edits not yet committed to the server: its current `updatedAt`
     * is meaningfully newer than the anchor recorded at its last sync. A missing sync-state row counts
     * as unsynced — with no proof the local copy was ever pushed, an undated tombstone must not delete
     * a notebook whose provenance we cannot establish.
     */
    private suspend fun hasUnsyncedLocalEdits(notebookId: String, notebook: Notebook): Boolean {
        val syncRow = appRepository.notebookSyncStateRepository.get(notebookId) ?: return true
        return notebook.updatedAt.time - syncRow.syncedLocalUpdatedAt.time >
            NotebookSyncPlanner.TOLERANCE_MS
    }

    /**
     * Remove orphaned remote notebook directories: a `/notebooks/<id>/` that has no manifest.json
     * and that we do NOT hold locally. This is the leftover of an interrupted upload (pages-first,
     * manifest-last) or a partial delete from *another* device, which the re-upload self-heal can't fix
     * (it only re-uploads notebooks we own locally). Strictly time-gated by the directory's own
     * last-modified: only
     * dirs older than [maxAgeDays] are touched, so an in-flight upload — whose dir is recent — is
     * never raced. Best-effort: failures are logged, never fatal to the run.
     */
    suspend fun garbageCollectOrphanedRemotes(
        client: WebDAVClient,
        localNotebookIds: Set<String>,
        maxAgeDays: Long
    ) {
        val cutoff = Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
        val entries = client.listCollectionWithMetadata(SyncPaths.notebooksDir()).getOrElse {
            log.w(TAG, "Orphan GC: listing notebooks failed: ${it.userMessage}")
            return
        }
        for (entry in entries) {
            val id = entry.name
            // Owned locally -> the normal sync re-uploads it to self-heal; never GC one we still have.
            if (id in localNotebookIds) continue
            // Unknown age -> don't risk deleting what might be an in-flight upload.
            val lastModified = entry.lastModified ?: continue
            if (!lastModified.before(cutoff)) continue
            // A manifest means a real, healthy notebook this device just doesn't have yet (it will be
            // downloaded as new) -> leave it. On an ambiguous error, assume present and skip.
            val hasManifest = client.exists(SyncPaths.manifestFile(id)).getOrElse { true }
            if (hasManifest) continue
            log.i(TAG, "Orphan GC: removing dead remote notebook $id (no manifest, older than $maxAgeDays days)")
            client.delete(SyncPaths.notebookDir(id)).onError {
                log.w(TAG, "Orphan GC: failed to remove $id: ${it.userMessage}")
            }
        }
    }

    suspend fun detectAndUploadLocalDeletions(
        client: WebDAVClient, preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Detecting local deletions...")
        // A notebook we recorded as synced but that is no longer local was deleted here.
        val syncedIds = appRepository.notebookSyncStateRepository.getAllIds()
        val deletedLocally = syncedIds - preDownloadNotebookIds
        val errors = ErrorAccumulator()

        // Safety guard: distinguish a real bulk delete from a stale-state divergence. If the local
        // notebook rows were lost while their sync-state rows survived (a partial DB reset, or rows
        // accumulated in download-only mode where deletions are never tombstoned), this set would
        // otherwise DELETE every one of them from the server -- for all devices. A genuine delete of
        // most of what we ever synced is rare; a wipe is unrecoverable, so refuse and surface it.
        if (looksLikeStaleStateWipe(deletedLocally.size, syncedIds.size)) {
            val message = "Refusing to delete ${deletedLocally.size} of ${syncedIds.size} notebooks " +
                "from the server: the local copies look missing, not intentionally deleted. Use " +
                "\"Download all\" to restore them before syncing deletions."
            log.e(TAG, message)
            return AppResult.Error(DomainError.SyncError(message, recoverable = false))
        }

        if (deletedLocally.isNotEmpty()) {
            log.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")
            for (notebookId in deletedLocally) {
                val notebookPath = SyncPaths.notebookDir(notebookId)
                // Unknown existence is recorded but does not stop the tombstone PUT below.
                val onServer = client.exists(notebookPath).onError { errors.add(it) }.getOrElse { false }
                if (onServer) {
                    log.i(TAG, "Deleting from server: $notebookId")
                    client.delete(notebookPath).onError { errors.add(it) }
                }
                client.putFile(
                    SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream"
                ).onSuccess {
                    log.i(TAG, "Tombstone uploaded for: $notebookId")
                    // Deletion propagated -- drop the sync-state rows so it is not detected again.
                    appRepository.notebookSyncStateRepository.delete(notebookId)
                    appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                }.onError { error ->
                    log.e(TAG, "Failed to upload tombstone for $notebookId: ${error.userMessage}")
                    errors.add(error)
                }
            }
        } else {
            log.i(TAG, "No local deletions detected")
        }

        return errors.asResult(deletedLocally.size)
    }

    suspend fun downloadNewNotebooks(
        client: WebDAVClient,
        tombstonedIds: Set<String>,
        preDownloadNotebookIds: Set<String>,
        remoteNotebookIds: Set<String>,
        downloadOnly: Boolean = false,
        bulkEnabled: Boolean = false,
        currentServerKey: String? = null,
        dirEtags: Map<String, ETag?> = emptyMap(),
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Checking server for new notebooks...")
        // In two-way/upload mode a remote notebook we previously synced but no longer hold locally
        // was DELETED here, so it must not be re-downloaded -- detectAndUploadLocalDeletions
        // tombstones it instead. In download-only mode local deletions are NOT authoritative (the
        // device mirrors the server and never tombstones), so a synced-but-locally-absent notebook
        // is a copy we lost, not a deletion: it must be re-downloaded. Applying the `synced` filter
        // there stranded every notebook whose local row was wiped while its sync-state row survived.
        val syncedIds = appRepository.notebookSyncStateRepository.getAllIds()
        // remoteNotebookIds is the single PROPFIND listing shared with reconciliation.
        val newNotebookIds = selectNewRemoteNotebookIds(
            remoteNotebookIds = remoteNotebookIds,
            localNotebookIds = preDownloadNotebookIds,
            tombstonedIds = tombstonedIds,
            syncedNotebookIds = syncedIds,
            downloadOnly = downloadOnly,
        )
        // Breakdown so a "0 new" is diagnosable: a short remote listing looks nothing like a full
        // one whose entries were all filtered out by the local/synced/tombstone sets.
        log.i(
            TAG,
            "New-notebook scan: remote=${remoteNotebookIds.size}, local=${preDownloadNotebookIds.size}, " +
                "synced=${syncedIds.size} (downloadOnly=$downloadOnly), " +
                "tombstoned=${tombstonedIds.size} -> ${newNotebookIds.size} new"
        )

        val errors = ErrorAccumulator()
        if (newNotebookIds.isNotEmpty()) {
            log.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
            val total = newNotebookIds.size
            newNotebookIds.forEachIndexed { i, notebookId ->
                reporter.beginItem(index = i + 1, total = total, name = notebookId, id = notebookId)
                downloadNotebook(notebookId, client).onError { errors.add(it) }.onSuccess {
                    // Establish the directory baseline for a freshly downloaded notebook so the next
                    // sync's fast path can skip its manifest GET. The listing ETag is the converged
                    // directory here; a null one just means no baseline yet.
                    val listingEtag = dirEtags[notebookId]
                    if (bulkEnabled && currentServerKey != null && listingEtag != null) {
                        safelyStoreDirectoryBaseline(
                            notebookId, listingEtag, currentServerKey
                        )
                    }
                }
            }
            reporter.endItem()
        } else {
            log.i(TAG, "No new notebooks on server")
        }

        return errors.asResult(newNotebookIds.size)
    }

    /** Baselines accelerate later syncs; failure to persist one must leave this download successful. */
    private suspend fun safelyStoreDirectoryBaseline(
        notebookId: String,
        etag: ETag,
        serverKey: String,
    ) {
        try {
            appRepository.notebookSyncStateRepository
                .updateRemoteDirBaseline(notebookId, etag.raw, serverKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Could not store directory baseline for $notebookId: ${e.message}")
        }
    }

    /**
     * Upload a notebook, recording an ERROR sync-state row on any failure so the library shows the
     * ERROR badge. A successful upload writes the SYNCED row inside [uploadNotebookInternal].
     */
    suspend fun uploadNotebook(
        notebook: Notebook,
        client: WebDAVClient,
        manifestIfMatch: ETag? = null
    ): AppResult<Unit, DomainError> {
        val result = uploadNotebookInternal(notebook, client, manifestIfMatch)
        if (result is AppResult.Error) {
            appRepository.notebookSyncStateRepository.markError(notebook.id, result.error.userMessage)
        }
        return result
    }

    /**
     * Whether [notebook] has any page changed locally since its last committed sync — the same
     * page-level dirty test [uploadNotebookInternal] uses, exposed for the reconcile path to decide
     * whether an upload half is even needed. Reconcile skips the upload (and its manifest republish)
     * when this is false, so two devices that have already converged don't churn the manifest ETag
     * back and forth and re-trigger each other's reconcile forever.
     */
    suspend fun hasLocallyDirtyPages(notebook: Notebook): Boolean {
        val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
        val rowsByPageId =
            appRepository.pageSyncStateRepository.getByNotebook(notebook.id).associateBy { it.pageId }
        return selectDirtyPages(pages, rowsByPageId).isNotEmpty()
    }

    /**
     * The manifest pages in a *genuine* conflict: edited locally since the last committed sync AND
     * changed on the server since then. Independent edits (only one side moved) are not returned —
     * those merge losslessly. Pure detection: one PROPFIND for page ETags plus local reads, no
     * content transfer, so it is cheap to recompute whenever the UI needs the current conflict set.
     *
     * A page with no sync row (never synced / new local page) or absent from the remote listing is
     * not a conflict here — those are creates/self-heals the normal reconcile handles. Only a
     * *provable* remote ETag change counts, so a server that omits ETags never invents a conflict.
     */
    suspend fun detectPageConflicts(
        notebook: Notebook,
        client: WebDAVClient
    ): AppResult<List<String>, DomainError> {
        val remoteEtags = client.listEtags(SyncPaths.pagesDir(notebook.id))
            .getOrElse { return AppResult.Error(it) }
        val rowsByPageId =
            appRepository.pageSyncStateRepository.getByNotebook(notebook.id).associateBy { it.pageId }
        val pagesById = appRepository.pageRepository.getByIds(notebook.pageIds).associateBy { it.id }
        val conflicts = notebook.pageIds.filter { pageId ->
            val row = rowsByPageId[pageId] ?: return@filter false
            val page = pagesById[pageId] ?: return@filter false
            val localDirty = page.updatedAt.time > row.syncedLocalUpdatedAt.time
            val remoteEtag = remoteEtags["$pageId.json"]
            val remoteChanged = remoteEtag != null && !ETag.parse(row.remoteEtag).matches(remoteEtag)
            localDirty && remoteChanged
        }
        return AppResult.Success(conflicts)
    }

    /**
     * Apply the user's [resolution] for one conflicted page by re-baselining its sync row, then
     * letting the next sync do the actual transfer through the normal reconcile path (which also
     * republishes the manifest and clears the CONFLICT state once every page is resolved).
     * Re-baselining instead of transferring inline keeps this cheap and keeps all the byte-moving and
     * commit logic in one place.
     *
     * - [PageConflictResolution.SKIP]: leave the row untouched — the page stays flagged and is asked
     *   again next sync.
     * - [PageConflictResolution.REPLACE_WITH_SERVER]: drop our page ETag and lift the change anchor to
     *   the local page's own timestamp, so it reads as "not locally dirty, remote differs" and the
     *   next reconcile fetches the server copy over it.
     * - [PageConflictResolution.UPLOAD_DB]: adopt the server's current page ETag as our base (so it is
     *   no longer a remote *change*) while leaving local content dirty, so the next reconcile pushes
     *   it — guarded by that adopted ETag, so it cleanly overwrites the server copy.
     */
    suspend fun resolveConflictedPage(
        notebookId: String,
        pageId: String,
        resolution: PageConflictResolution,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val row = appRepository.pageSyncStateRepository.getByNotebook(notebookId)
            .firstOrNull { it.pageId == pageId }
            ?: return AppResult.Error(DomainError.NotFound("page sync row for $pageId"))
        val rebased = when (resolution) {
            PageConflictResolution.SKIP -> return AppResult.Success(Unit)
            PageConflictResolution.REPLACE_WITH_SERVER -> {
                val localUpdatedAt =
                    appRepository.pageRepository.getById(pageId)?.updatedAt ?: row.syncedLocalUpdatedAt
                row.copy(remoteEtag = null, syncedLocalUpdatedAt = localUpdatedAt)
            }

            PageConflictResolution.UPLOAD_DB -> {
                val remoteEtag = client.listEtags(SyncPaths.pagesDir(notebookId))
                    .getOrElse { return AppResult.Error(it) }["$pageId.json"]
                row.copy(remoteEtag = remoteEtag?.raw)
            }
        }
        appRepository.pageSyncStateRepository.upsertAll(listOf(rebased))
        return AppResult.Success(Unit)
    }

    /**
     * Whether two manifests disagree on any authoritative notebook field that cannot be merged page by
     * page — every serialized field except page *content* (which merges per page) and `updatedAt`, the
     * change clock the tolerance window already reasons about rather than a value to conflict on.
     * Device-local navigation state (`openPageId`) is not serialized at all, so it never enters here.
     *
     * A pure download would overwrite the local side of any of the compared fields, so a divergence
     * here is surfaced as a whole-notebook conflict rather than silently resolved.
     */
    fun structurallyDiverges(local: Notebook, remote: Notebook): Boolean =
        local.pageIds != remote.pageIds ||
            local.title != remote.title ||
            local.parentFolderId != remote.parentFolderId ||
            local.defaultBackground != remote.defaultBackground ||
            local.defaultBackgroundType != remote.defaultBackgroundType ||
            local.defaultSheet != remote.defaultSheet ||
            local.linkedExternalUri != remote.linkedExternalUri ||
            local.createdAt != remote.createdAt

    /**
     * The full conflict picture for [notebook] against the server: the same-page conflicts from
     * [detectPageConflicts] plus whether the manifests [structurallyDiverges]. Read-only; used by the
     * resolution UI, so it fetches the remote manifest fresh. A remote manifest that cannot be read or
     * parsed is reported as a structural conflict — the safe reading when we cannot prove the
     * structures match.
     */
    suspend fun detectConflicts(
        notebook: Notebook,
        client: WebDAVClient
    ): AppResult<NotebookConflict, DomainError> {
        val conflictIds = detectPageConflicts(notebook, client).getOrElse { return AppResult.Error(it) }
        val pageConflicts = conflictIds.map { pageId ->
            PageConflict(pageId = pageId, pageNumber = notebook.pageIds.indexOf(pageId) + 1)
        }
        val remote = client.getFile(SyncPaths.manifestFile(notebook.id))
            .flatMap { NotebookSerializer.deserializeManifest(it.decodeToString()) }
            .getOrElse { return AppResult.Success(NotebookConflict(pageConflicts, structural = true)) }
        return AppResult.Success(NotebookConflict(pageConflicts, structurallyDiverges(notebook, remote)))
    }

    /**
     * Apply a whole-notebook [resolution] for a structural conflict, then let the next sync do the
     * transfer (as with [resolveConflictedPage]).
     *
     * - [NotebookConflictResolution.TAKE_SERVER]: download the server notebook over the local copy,
     *   which commits it as synced and clears the CONFLICT state.
     * - [NotebookConflictResolution.KEEP_LOCAL]: adopt the server's current manifest and page ETags
     *   as our base *without* advancing the change anchor, so the still-newer local copy is uploaded
     *   next sync and cleanly overwrites the server — guarded by those adopted ETags, so it wins.
     */
    suspend fun resolveNotebookConflict(
        notebookId: String,
        resolution: NotebookConflictResolution,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> = when (resolution) {
        NotebookConflictResolution.TAKE_SERVER -> downloadNotebook(notebookId, client)
        NotebookConflictResolution.KEEP_LOCAL -> adoptRemoteAsBase(notebookId, client)
    }

    /**
     * Re-anchor [notebookId]'s sync rows to the server's current ETags (manifest and every page)
     * while keeping the previous change anchor, so the local copy still reads as dirty and is uploaded
     * next sync — but now guarded by up-to-date ETags, so the upload overwrites the server instead of
     * 412-ing. Used by [resolveNotebookConflict] to force the local version.
     */
    private suspend fun adoptRemoteAsBase(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val manifest = client.getFileWithMetadata(SyncPaths.manifestFile(notebookId))
            .getOrElse { return AppResult.Error(it) }
        val remotePageEtags = client.listEtags(SyncPaths.pagesDir(notebookId))
            .getOrElse { return AppResult.Error(it) }
        val rebasedRows = appRepository.pageSyncStateRepository.getByNotebook(notebookId).map { row ->
            remotePageEtags["${row.pageId}.json"]?.let { row.copy(remoteEtag = it.raw) } ?: row
        }
        appRepository.pageSyncStateRepository.upsertAll(rebasedRows)
        appRepository.notebookSyncStateRepository.rebaselineToRemoteEtag(notebookId, manifest.etag?.raw)
        return AppResult.Success(Unit)
    }

    private suspend fun uploadNotebookInternal(
        notebook: Notebook,
        client: WebDAVClient,
        manifestIfMatch: ETag? = null
    ): AppResult<Unit, DomainError> {
        val notebookId = notebook.id
        log.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        return client.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/").flatMap {
            client.createCollection(SyncPaths.imagesDir(notebookId))
        }.flatMap {
            client.createCollection(SyncPaths.backgroundsDir(notebookId))
        }.flatMap {
            // 1. Upload only the DIRTY pages FIRST. Pages unchanged since their last
            //    committed sync are skipped -- editing one page of an 800-page notebook now PUTs one
            //    page, not 800. Skipped pages keep their existing page_sync_state row.
            val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
            val rowsByPageId =
                appRepository.pageSyncStateRepository.getByNotebook(notebookId).associateBy { it.pageId }
            // A page unchanged locally can still be MISSING on the server: another device deleted its
            // file (or the whole remote directory), yet its stale sync row would skip it and we'd
            // republish a manifest pointing at a file that no longer exists -- and the remote would
            // never self-heal. List the remote pages once (names AND ETags: the ETags also guard the
            // rowless-but-present case below) and force-upload any manifest page absent from that
            // listing. If the listing can't be read we can't prove presence, so re-upload every page
            // (safe and self-healing; also covers a wholly vanished remote directory).
            val remotePageEtags = client.listEtags(SyncPaths.pagesDir(notebookId))
                .getOrElse { error ->
                    log.w(TAG, "Remote page listing failed for ${notebook.title}; uploading all pages: ${error.userMessage}")
                    null
                }
            val locallyDirtyIds = selectDirtyPages(pages, rowsByPageId).map { it.id }.toSet()
            // Pages the listing proved absent (only when the listing succeeded -- a failed listing
            // proves nothing, so guard those normally). Their stored ETag is stale, so they are
            // recreated with an `If-None-Match: *` create guard below rather than the stale If-Match:
            // the write lands only if the page is still absent, and a page another device recreated
            // first 412s and aborts this sync to re-plan instead of being overwritten.
            val knownAbsentPageIds = if (remotePageEtags == null) emptySet()
            else notebook.pageIds.filter { "$it.json" !in remotePageEtags }.toSet()
            val dirtyPages = pages.filter { page ->
                page.id in locallyDirtyIds || page.id in knownAbsentPageIds
            }
            log.i(TAG, "${dirtyPages.size}/${pages.size} page(s) dirty, uploading")

            val errors = ErrorAccumulator()
            val committedPageRows = mutableListOf<PageSyncState>()
            for (page in dirtyPages) {
                // Guard the dirty-page PUT with the stored ETag. A concurrent remote
                // change to this page 412s, aborting before manifest publish (nothing committed).
                // A page proven absent is recreated with an `If-None-Match: *` create guard instead,
                // which 412s (and likewise aborts) if another device recreated it first.
                // A page with no committed row but present on the server (e.g. the first edit after the
                // 35->36 migration created an empty sync table) is guarded with the ETag from the
                // listing, so a concurrent update 412s instead of being silently overwritten. Only a
                // genuinely new page -- no row and absent from the listing -- uploads unguarded, and a
                // server that omits the ETag leaves the present-but-rowless case unguarded too, the
                // same last-writer-wins policy already in force where no validator exists.
                val createOnly = page.id in knownAbsentPageIds
                val storedRow = rowsByPageId[page.id]
                val ifMatch = when {
                    createOnly -> null
                    storedRow != null -> ETag.parse(storedRow.remoteEtag)
                    else -> remotePageEtags?.get("${page.id}.json")
                }
                val uploaded =
                    uploadPage(page, notebookId, client, ifMatch, createOnly).onSuccess { pageEtag ->
                    committedPageRows.add(
                        PageSyncState(
                            pageId = page.id,
                            notebookId = notebookId,
                            remoteEtag = pageEtag?.raw,
                            syncedLocalUpdatedAt = page.updatedAt,
                            lastSyncedAt = Date(),
                        )
                    )
                }.onError { errors.add(it) }
                // A confirmed remote edit to one page settles the whole notebook: the manifest can
                // no longer be published, so uploading the remaining pages would only push bytes at
                // a commit that is already lost. Stop and let the next sync re-plan.
                if (uploaded is AppResult.Error && uploaded.error is DomainError.SyncConflict) {
                    log.w(TAG, "Aborting upload of ${notebook.title}: page ${page.id} changed remotely")
                    break
                }
            }
            // Rows for pages that left the notebook are dropped in the commit transaction.
            val departedPageIds = rowsByPageId.keys - notebook.pageIds.toSet()

            // 2. If any page failed, do NOT publish the manifest. Leaving the old commit marker in
            //    place keeps the notebook "not yet updated" for other devices and makes this device
            //    re-upload on the next sync -- never a manifest pointing at missing/stale pages.
            if (errors.hasErrors) {
                errors.asResult(Unit)
            } else {
                // 3. All dirty pages are up: publish the manifest last. This is the atomic commit;
                //    the If-Match guard rejects the publish if the remote changed since we read it.
                //    Capture the new ETag so next sync can do a cheap If-None-Match check.
                val manifestJson = NotebookSerializer.serializeManifest(notebook)
                publishManifest(notebookId, manifestJson.toByteArray(), manifestIfMatch, client)
                    .onSuccess { newEtag ->
                    // If the server returned no ETag on any page PUT, one PROPFIND on the pages dir
                    // backfills them so the stored rows can drive next sync's skip.
                    backfillMissingPageEtags(notebookId, committedPageRows, client)
                    // Commit point: mark the notebook synced,
                    // write the uploaded pages' rows, and drop departed rows -- all in one
                    // transaction. After upload the remote's updatedAt equals the manifest we just
                    // wrote (notebook.updatedAt).
                    appRepository.commitNotebookSync(
                        notebookId = notebookId,
                        localUpdatedAt = notebook.updatedAt,
                        remoteUpdatedAt = notebook.updatedAt,
                        manifestEtag = newEtag?.raw,
                        committedPageRows = committedPageRows,
                        departedPageIds = departedPageIds.toList(),
                    )
                    // Only after a committed upload: drop any stale tombstone for a resurrected
                    // notebook. Best-effort -- a leftover tombstone is re-checked next sync.
                    val tombstonePath = SyncPaths.tombstone(notebookId)
                    if (client.exists(tombstonePath).getOrElse { false }) {
                        client.delete(tombstonePath).onSuccess {
                            log.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
                        }.onError {
                            log.w(TAG, "Failed to remove stale tombstone $notebookId: ${it.userMessage}")
                        }
                    }
                    log.i(TAG, "Uploaded: ${notebook.title}")
                    // Best-effort GC: remove remote page/image/background files no longer
                    // referenced by the manifest we just committed. Never fails the upload.
                    //
                    // Only after a *guarded* commit. Unguarded (no/weak stored ETag) the manifest
                    // publish is last-writer-wins, so this GC could delete a page a concurrent
                    // device just uploaded while that device's own unguarded publish still succeeds
                    // -- leaving the final manifest pointing at a file we removed. A guarded commit
                    // cannot be reached under a concurrent remote change (it would 412 and abort),
                    // so our manifest snapshot is current and pruning against it is safe. On no-ETag
                    // servers orphan files instead linger until a later guarded upload or the
                    // time-gated orphan sweep.
                    when (val guard = manifestIfMatch.writeGuard()) {
                        is WriteGuard.Guarded -> garbageCollectRemote(notebook, client)
                        is WriteGuard.Unguarded -> log.i(
                            TAG,
                            "Skipping remote GC for ${notebook.title}: commit unguarded (${guard.explain()})"
                        )
                    }
                }.map { }
            }
        }
    }

    /**
     * Fill in ETags for uploaded pages whose PUT response carried none, via one Depth-1 PROPFIND on
     * the pages directory. A page row with a null ETag would force a re-download next sync (its
     * stored ETag never matches the server's), so this keeps the skip path effective on servers that
     * don't echo an ETag on PUT. Best-effort: on PROPFIND failure the rows keep their null ETags.
     */
    private suspend fun backfillMissingPageEtags(
        notebookId: String,
        rows: MutableList<PageSyncState>,
        client: WebDAVClient
    ) {
        if (rows.none { it.remoteEtag == null }) return
        val etagsByName = client.listEtags(SyncPaths.pagesDir(notebookId)).getOrElse {
            log.w(TAG, "Page ETag backfill PROPFIND failed: ${it.userMessage}")
            return
        }
        for (i in rows.indices) {
            val row = rows[i]
            if (row.remoteEtag == null) {
                etagsByName["${row.pageId}.json"]?.let { rows[i] = row.copy(remoteEtag = it.raw) }
            }
        }
    }

    /**
     * Publish the manifest atomically: PUT to a `.tmp` sibling (a full write that never touches the
     * live commit marker), then MOVE it over `manifest.json`. This closes the only interruption
     * window a plain PUT leaves open — a torn PUT of the manifest itself would otherwise leave a corrupt
     * commit marker. Falls back to a direct guarded PUT when the server doesn't support MOVE.
     *
     * A 412 during MOVE is a genuine concurrency conflict and is propagated (not retried) — **but
     * only when we actually sent a precondition.** A 412 we did not ask for came from something
     * else (a proxy, or a server precondition of its own) and must not be read as ours: the conflict
     * branch has no fallback by design, so an unattributable 412 would abort every sync of this
     * notebook permanently. Unguarded, we fall through to the direct PUT and its last-writer-wins,
     * which is the policy already in force wherever there is no usable validator.
     *
     * The tmp file is cleaned up best-effort; if left behind (interrupted before MOVE) it is simply
     * overwritten by the next upload.
     *
     * Returns the published manifest's ETag when known. MOVE carries no destination ETag (RFC 4918),
     * so it is learned one of two ways: on a server measured to preserve the ETag across MOVE
     * ([ServerCapabilities.movePreservesEtag] for the current server key) the tmp PUT's ETag *is* the
     * destination's and is returned directly, saving a readback; otherwise a `PROPFIND Depth:0` reads
     * it back. Either can be `null` (server issued no ETag, or a failed readback) — self-correcting,
     * since the next sync's conditional GET returns a body on mismatch and re-establishes the
     * validator.
     */
    private suspend fun publishManifest(
        notebookId: String,
        manifestBytes: ByteArray,
        ifMatch: ETag?,
        client: WebDAVClient
    ): AppResult<ETag?, DomainError> {
        val finalPath = SyncPaths.manifestFile(notebookId)
        val tmpPath = "$finalPath.tmp"
        // Whether we actually sent a precondition decides how to read a 412 below.
        val guard = ifMatch.writeGuard()
        // Snapshot the capability before publishing. Most importantly, bind it to this client's
        // endpoint rather than mutable settings that the user can edit while a sync is running.
        val reuseMovedEtag = movePreservesEtag(client)

        // Capture the tmp PUT's ETag so it can be reused as the destination's on a server measured to
        // preserve ETags across MOVE, avoiding the readback PROPFIND below.
        val tmpEtag = client.putFileReturningEtag(tmpPath, manifestBytes, "application/json")
            .getOrElse { return AppResult.Error(it) }

        return when (val moved = client.move(tmpPath, finalPath, ifMatchDestination = ifMatch)) {
            is AppResult.Success -> {
                // MOVE carries no ETag for its destination (RFC 4918). When the server is measured to
                // keep the tmp file's ETag across MOVE, that captured ETag *is* the manifest's now --
                // reuse it and skip the read. Otherwise read it back with a cheap PROPFIND Depth:0.
                // Without a validator the row commits unguarded even on servers that DO issue ETags
                // (e.g. Nextcloud) -- forfeiting next sync's conditional-304 skip and the guarded
                // remote GC. A wrong reused ETag self-corrects on the next conditional GET; a failed
                // readback degrades to null (unguarded, but the MOVE already succeeded), never
                // failing the publish.
                val destEtag = if (tmpEtag != null && reuseMovedEtag) {
                    tmpEtag
                } else {
                    client.resourceEtag(finalPath).getOrElse { null }
                }
                if (destEtag == null) {
                    log.w(TAG, "Manifest ETag unknown for $notebookId; commit will be unguarded")
                }
                AppResult.Success(destEtag)
            }
            is AppResult.Error -> {
                client.delete(tmpPath) // best-effort cleanup either way
                if (moved.error is DomainError.SyncConflict && guard is WriteGuard.Guarded) {
                    // Destination changed under us -- a real conflict, do not fall back.
                    moved
                } else {
                    // MOVE unsupported or transient: fall back to a direct guarded PUT (the manifest
                    // is still written last, so ordering is preserved; only atomicity is lost).
                    log.w(TAG, "MOVE publish failed (${moved.error.userMessage}); direct PUT fallback")
                    client.putFileReturningEtag(finalPath, manifestBytes, "application/json", ifMatch)
                }
            }
        }
    }

    /**
     * Whether the current server was *measured* to keep a resource's ETag across a MOVE, so the tmp
     * PUT's ETag can stand in for the post-MOVE destination ETag. Only trusted when the stored
     * capability's key matches the server about to be written — a record from a different server (URL
     * or username) is stale and ignored. Any missing piece answers `false`, keeping the safe readback.
     */
    private suspend fun movePreservesEtag(client: WebDAVClient): Boolean {
        return try {
            canReuseMovedEtag(kvProxy.getServerCapabilities(), client.serverKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Capability storage is optional optimization state. A read/decode/database failure
            // must not fail a manifest upload; retain the safe destination-Etag readback instead.
            log.w(TAG, "Could not read MOVE capability; using manifest ETag readback: ${e.message}")
            false
        }
    }

    /**
     * Run [block] with a fresh temp file in the cache dir, deleting it afterwards whatever happens.
     *
     * Every page transfer needs a scratch file and nothing more: serialize-then-PUT ([uploadPage])
     * and fetch-the-remote-copy to settle a 412 ([resolvePageConflict]). Bounding page memory means
     * going through disk, so this lifetime recurs on every path that moves a page.
     *
     * `inline` so [block] can `return` out of the calling function, which the conflict path does.
     */
    private inline fun <T> withPageTempFile(prefix: String, block: (File) -> T): T {
        val file = File.createTempFile(prefix, ".json", context.cacheDir)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    /**
     * Decide what a page-level `If-Match` 412 actually means, on **content** rather than on the
     * ETag that just failed to match.
     *
     * A stale stored ETag has two benign causes, and in both the remote file already holds exactly
     * the bytes we were about to send:
     *  - our own earlier sync PUT this page and then failed on a later step, so its row was never
     *    committed and still carries the pre-PUT ETag;
     *  - the server regenerated the ETag without a content change (or spells it differently between
     *    PUT and PROPFIND than we stored).
     *
     * Anything else is a real concurrent edit from another device. Neither blanket answer is safe.
     * Overwriting destroys that device's page content on the server while its manifest still points
     * at it — the manifest guard makes our *commit* fail, but the page bytes are already gone.
     * Aborting wedges the notebook forever, because cause 1 would 412 identically on every future
     * sync.
     *
     * So: fetch the remote page (streamed to a temp file, never buffered whole) and compare.
     *  - identical -> nothing to upload. Report success carrying the remote's own ETag, which is the
     *    correct anchor for this page's committed row.
     *  - different -> propagate [DomainError.SyncConflict]. The manifest is never published, nothing
     *    is committed, and the next sync re-reads the remote so the planner can decide
     *    download-vs-upload with fresh information.
     *  - could not check -> propagate the conflict as well: refusing to overwrite is the safe default
     *    when we cannot prove the remote is ours.
     */
    private fun resolvePageConflict(
        pageId: String,
        pagePath: String,
        localCopy: File,
        client: WebDAVClient,
    ): AppResult<ETag?, DomainError> {
        return withPageTempFile("notable-page-remote-") { remoteCopy ->
            val remoteEtag = when (val fetched = client.getFile(pagePath, remoteCopy)) {
                is AppResult.Success -> fetched.data
                is AppResult.Error -> {
                    log.w(
                        TAG,
                        "Page $pageId 412 and its remote copy could not be read " +
                            "(${fetched.error.userMessage}); treating as a conflict"
                    )
                    return AppResult.Error(DomainError.SyncConflict)
                }
            }
            if (contentsEqual(localCopy, remoteCopy)) {
                log.i(TAG, "Page $pageId 412 but remote content is identical; adopting its ETag")
                AppResult.Success(remoteEtag)
            } else {
                log.w(TAG, "Page $pageId changed remotely since our last sync; aborting upload")
                AppResult.Error(DomainError.SyncConflict)
            }
        }
    }

    /** Byte equality of two files, streamed via digests so page size never bounds memory. */
    private fun contentsEqual(a: File, b: File): Boolean =
        a.length() == b.length() && sha256(a).contentEquals(sha256(b))

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /**
     * Publish one page atomically: PUT to a per-attempt staging sibling (a full write that never
     * becomes visible at the live page name), then MOVE it over `<pageId>.json`. A plain PUT straight
     * to the final name can be torn by an interrupted transfer, leaving a truncated page file that
     * every device then fails to parse — the same window the manifest closes with .tmp+MOVE
     * ([publishManifest]).
     *
     * The staging name carries a random suffix so two devices publishing the same page never share a
     * source file. A shared staging path is a data-loss race: device B could overwrite the bytes A
     * staged, then A MOVEs B's content while its *destination* guard still matches, records the move
     * as its own upload, and backfills the server's (B's) ETag — leaving A permanently "synced" to
     * content it never wrote. A unique source means each device only ever MOVEs its own bytes; the
     * loser's MOVE 412s on the now-changed destination and routes to [resolvePageConflict].
     *
     * The [ifMatch] guard rides the MOVE's destination precondition. A 412 there is a genuine
     * concurrent remote change and is settled by [resolvePageConflict] (byte-compare of our copy
     * against the remote) — but only when we actually sent a precondition; an unattributable 412
     * (weak/absent validator) falls through instead of being read as ours. When MOVE is unsupported
     * (405/501) or fails transiently, fall back to the direct guarded PUT (atomicity lost, ordering
     * preserved); its own 412 is resolved the same way.
     *
     * [createOnly] publishes a page believed absent: the MOVE is sent non-overwriting (`Overwrite: F`)
     * and unguarded, so a page another device created in the meantime makes the server reject the
     * publish. That rejection is a genuine concurrent create and is surfaced as
     * [DomainError.SyncConflict] — never content-resolved, since with no stored ETag there is nothing
     * to trust — so the caller aborts and re-plans. The MOVE-unsupported fallback carries [createOnly]
     * into the direct PUT's `If-None-Match: *` guard, preserving the same semantics.
     *
     * The MOVE path returns a null ETag — the destination's post-move ETag isn't reliably reported —
     * which [backfillMissingPageEtags] fills in with one PROPFIND so the skip path stays effective.
     * The staging file is deleted best-effort on failure; a MOVE consumes it on success, and any
     * leftover (hard crash before MOVE) is swept by the next upload's [garbageCollectRemote], which
     * deletes every pages-dir entry the committed manifest doesn't reference.
     */
    private fun publishPage(
        pageId: String,
        pagePath: String,
        localCopy: File,
        ifMatch: ETag?,
        createOnly: Boolean,
        client: WebDAVClient,
    ): AppResult<ETag?, DomainError> {
        val tmpPath = "$pagePath.${UUID.randomUUID()}.tmp"
        // Whether we actually sent an update precondition decides how to read a 412 below.
        val guard = ifMatch.writeGuard()

        client.putFile(tmpPath, localCopy, "application/json")
            .onFailure { return AppResult.Error(it) }

        val moved = client.move(
            tmpPath, pagePath, ifMatchDestination = ifMatch, overwrite = !createOnly
        )
        return when (moved) {
            is AppResult.Success -> AppResult.Success(null)
            is AppResult.Error -> {
                client.delete(tmpPath) // best-effort cleanup either way
                when {
                    // Guarded update whose destination changed under us: settle it on content.
                    moved.error is DomainError.SyncConflict && guard is WriteGuard.Guarded ->
                        resolvePageConflict(pageId, pagePath, localCopy, client)

                    // Non-overwriting create rejected because the page now exists (another device
                    // created it first): a real conflict, never content-resolved. Surface it as-is.
                    moved.error is DomainError.SyncConflict && createOnly -> moved

                    else -> {
                        // MOVE unsupported or transient: fall back to a direct PUT carrying the same
                        // guard/create precondition, and resolve its update-412 the same way.
                        log.w(TAG, "MOVE publish failed for page $pageId (${moved.error.userMessage}); direct PUT fallback")
                        val guarded = client.putFileReturningEtag(
                            pagePath, localCopy, "application/json", ifMatch, createOnly
                        )
                        if (guarded is AppResult.Error && guarded.error is DomainError.SyncConflict &&
                            guard is WriteGuard.Guarded
                        ) {
                            resolvePageConflict(pageId, pagePath, localCopy, client)
                        } else {
                            guarded
                        }
                    }
                }
            }
        }
    }

    /**
     * Upload one page's JSON plus its images/backgrounds, returning the page file's new server ETag
     * (or `null` when the atomic publish couldn't report one — see [publishPage]). The page is
     * published atomically via [publishPage]; [ifMatch] guards it against a concurrent remote change
     * and a 412 is resolved by [resolvePageConflict]. Pass `null` for a page with no prior sync row.
     * [createOnly] publishes a page believed absent with a create guard; a concurrent create surfaces
     * as [DomainError.SyncConflict] so the caller aborts and re-plans (see [publishPage]).
     */
    private suspend fun uploadPage(
        page: Page,
        notebookId: String,
        client: WebDAVClient,
        ifMatch: ETag? = null,
        createOnly: Boolean = false
    ): AppResult<ETag?, DomainError> {
        val pageWithData =
            appRepository.pageRepository.getWithDataById(page.id) ?: return AppResult.Error(
                DomainError.DatabaseError("Page data not found for page ID: ${page.id}")
            )
        // Serialize the page to a temp file (streaming, one stroke at a time) and stream that file
        // to the PUT, rather than building a whole-page JSON string + toByteArray() in memory. This
        // bounds upload memory to ~one stroke regardless of page size — a 12k-stroke page otherwise
        // materialised its point data several times over and OOM'd.
        val pagePath = SyncPaths.pageFile(notebookId, page.id)
        // The temp file must outlive the PUT: resolvePageConflict compares the remote against these
        // same bytes, so both live inside one withPageTempFile block.
        val pageUploaded = withPageTempFile("notable-page-") { tempFile ->
            tempFile.outputStream().buffered().use { out ->
                NotebookSerializer.serializePage(page, pageWithData.strokes, pageWithData.images, out)
            }
            publishPage(page.id, pagePath, tempFile, ifMatch, createOnly, client)
        }
        return pageUploaded.flatMap { pageEtag ->
            val errors = ErrorAccumulator()
            for (image in pageWithData.images) {
                if (!image.uri.isNullOrEmpty()) {
                    // Normalize the URI: some rows store a `file://` scheme that File() can't resolve,
                    // which silently skipped the upload and left a dangling reference.
                    val localFile = resolveLocalFile(image.uri)
                    if (localFile.exists()) {
                        val remotePath = SyncPaths.imageFile(notebookId, localFile.name)
                        // Unknown existence -> upload anyway; PUT is idempotent.
                        if (!client.exists(remotePath).getOrElse { false }) {
                            client.putFile(remotePath, localFile, detectMimeType(localFile))
                                .onSuccess {
                                    log.i(TAG, "Uploaded image: ${localFile.name}")
                                }.onError { errors.add(it) }
                        }
                    } else {
                        // Dangling reference: the page references an image we can't find locally, so
                        // it will 404 on every other device. Surface it.
                        log.w(TAG, "Image referenced by page but missing locally: ${image.uri}")
                    }
                }
            }

            // Linked external PDFs (absolute path outside managed storage) can't be synced -- skip
            // them; the reference is left as-is and is non-fatal on download.
            if (page.backgroundType != "native" && page.background != "blank" &&
                !File(page.background).isAbsolute
            ) {
                val bgFile = File(ensureBackgroundsFolder(), page.background)
                if (bgFile.exists()) {
                    val remotePath = SyncPaths.backgroundFile(notebookId, bgFile.name)
                    if (!client.exists(remotePath).getOrElse { false }) {
                        client.putFile(remotePath, bgFile, detectMimeType(bgFile)).onSuccess {
                            log.i(TAG, "Uploaded background: ${bgFile.name}")
                        }.onError { errors.add(it) }
                    }
                } else {
                    log.w(TAG, "Background referenced by page but missing locally: ${page.background}")
                }
            }

            errors.asResult(pageEtag)
        }
    }

    /**
     * Download a notebook, recording an ERROR sync-state row on any failure so the library shows
     * the ERROR badge. A successful download writes the SYNCED row inside
     * [downloadNotebookInternal].
     */
    suspend fun downloadNotebook(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val result = downloadNotebookInternal(notebookId, client)
        if (result is AppResult.Error) {
            appRepository.notebookSyncStateRepository.markError(notebookId, result.error.userMessage)
        }
        return result
    }

    private suspend fun downloadNotebookInternal(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Downloading notebook ID: $notebookId")

        // 1. Fetch manifest file with its ETag (Early Return on error). The ETag is stored at the
        //    commit point so next sync can do a cheap If-None-Match check.
        val manifestFile = client.getFileWithMetadata(SyncPaths.manifestFile(notebookId))
            .onFailure { return AppResult.Error(it) }
        val remoteEtag = manifestFile.etag

        // 2. Deserialize manifest (Early Return on corrupted JSON)
        val manifestJson = manifestFile.content.decodeToString()
        val notebook = NotebookSerializer.deserializeManifest(manifestJson)
            .onFailure { return AppResult.Error(it) }

        log.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

        // 3. Ensure a notebook row exists so page foreign keys resolve, but do NOT advance its
        //    commit timestamp yet. A brand-new notebook is inserted with an epoch-0 timestamp so a
        //    partial download reads as "older than remote" and re-downloads next sync instead of
        //    being skipped as in-sync. An existing notebook keeps its current
        //    (older) timestamp untouched.
        // openPageId is device-local navigation state and is not carried in the manifest, so the
        // deserialized notebook has none. Preserve whatever this device last had open across the
        // download instead of resetting it.
        val existingBook = appRepository.bookRepository.getById(notebookId)
        val isNew = existingBook == null
        if (isNew) {
            try {
                appRepository.bookRepository.createEmpty(notebook.copy(updatedAt = Date(0)))
            } catch (e: Exception) {
                return AppResult.Error(DomainError.DatabaseError("Failed to create notebook locally: ${e.message}"))
            }
        }

        // 4. One Depth-1 PROPFIND on the pages dir gives every page's current remote ETag, so we can
        //    fetch only the pages that changed since our last committed sync. On PROPFIND
        //    failure the map is empty -> every page's current ETag reads null -> all pages fetched
        //    which is safe, just not economical.
        val currentEtagByPageId = client.listEtags(SyncPaths.pagesDir(notebookId)).getOrElse {
            log.w(TAG, "Page listing PROPFIND failed for ${notebook.title}; fetching all pages: ${it.userMessage}")
            emptyMap<String, ETag?>()
        }.mapNotNull { (name, etag) ->
            if (name.endsWith(".json")) name.removeSuffix(".json") to etag else null
        }.toMap()
        // A server that lists pages but omits <getetag> makes download-skip a silent no-op (every page
        // re-fetched every sync). Correct, just not economical -- surface it once so it's diagnosable.
        if (currentEtagByPageId.isNotEmpty() && currentEtagByPageId.values.all { it == null }) {
            log.w(TAG, "Server returned no page ETags for ${notebook.title}; download-skip disabled (fetching all)")
        }
        val rowsByPageId =
            appRepository.pageSyncStateRepository.getByNotebook(notebookId).associateBy { it.pageId }
        // Which of the manifest's pages we actually hold locally. A `page_sync_state` row is not
        // proof the page row still exists (it may have been deleted locally while offline, or the
        // notebook restored from a backup that predates it), and skipping a page we don't have would
        // leave the notebook permanently referencing a page with no content.
        val localPageIds =
            appRepository.pageRepository.getByIds(notebook.pageIds).map { it.id }.toSet()

        // 5. Download and persist the changed pages; skipped pages keep their local content and row.
        val errors = ErrorAccumulator()
        val committedPageRows = mutableListOf<PageSyncState>()
        // Backgrounds are often shared across pages (e.g. a PDF); fetch each distinct one once.
        val attemptedBackgrounds = mutableSetOf<String>()
        var fetched = 0
        for (pageId in notebook.pageIds) {
            val currentEtag = currentEtagByPageId[pageId]
            val storedEtag = ETag.parse(rowsByPageId[pageId]?.remoteEtag)
            // An unreadable remote ETag is spelled out rather than left to `matches` returning
            // false for a null, because "we could not tell" is a different reason to fetch than
            // "it differs" and should stay visible here.
            val needsFetch = rowsByPageId[pageId] == null ||
                pageId !in localPageIds ||
                currentEtag == null ||
                !storedEtag.matches(currentEtag)
            if (!needsFetch) continue
            fetched++
            downloadPage(pageId, notebookId, client, attemptedBackgrounds).onSuccess { pageUpdatedAt ->
                committedPageRows.add(
                    PageSyncState(
                        pageId = pageId,
                        notebookId = notebookId,
                        remoteEtag = currentEtag?.raw,
                        syncedLocalUpdatedAt = pageUpdatedAt,
                        lastSyncedAt = Date(),
                    )
                )
            }.onError { errors.add(it) }
        }
        log.i(TAG, "Downloaded $fetched/${notebook.pageIds.size} changed page(s) for ${notebook.title}")

        // 6. Commit: only when every fetched page landed, write the notebook row with the real remote
        //    timestamp. On any failure the notebook keeps its old/sentinel timestamp, so the next
        //    sync retries the download rather than treating the hole as "in sync".
        if (errors.hasErrors) {
            log.w(TAG, "Download incomplete for ${notebook.title}; leaving timestamp stale to retry")
            return errors.asResult(Unit)
        }
        val departedPageIds =
            (rowsByPageId.keys - notebook.pageIds.toSet()).toList()
        return try {
            appRepository.bookRepository.updateVerbatim(notebook.copy(openPageId = existingBook?.openPageId))
            // Commit point: mark the notebook synced at the remote
            // timestamp, write the fetched pages' rows, and drop rows for departed pages -- one
            // transaction. Skipped pages keep their prior rows untouched.
            appRepository.commitNotebookSync(
                notebookId = notebookId,
                localUpdatedAt = notebook.updatedAt,
                remoteUpdatedAt = notebook.updatedAt,
                manifestEtag = remoteEtag?.raw,
                committedPageRows = committedPageRows,
                departedPageIds = departedPageIds,
            )
            // Best-effort GC: delete local pages that are no longer in the downloaded manifest.
            pruneLocalOrphanPages(notebook)
            log.i(TAG, "Downloaded: ${notebook.title}")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error(DomainError.DatabaseError("Failed to commit notebook $notebookId: ${e.message}"))
        }
    }

    /**
     * Fetch, persist, and return one page's `updatedAt` (used as its `page_sync_state` anchor). A
     * permanently-missing media file (404) is non-fatal; a transient media error is aggregated so
     * the page is retried next sync.
     */
    private suspend fun downloadPage(
        pageId: String,
        notebookId: String,
        client: WebDAVClient,
        attemptedBackgrounds: MutableSet<String>
    ): AppResult<Date, DomainError> {

        // 1. Fetch JSON file (Early Return on error)
        val pageBytes = client.getFile(SyncPaths.pageFile(notebookId, pageId))
            .onFailure { return AppResult.Error(it) }

        // 2. Deserialize (Early Return on corrupted JSON)
        val pageJson = pageBytes.decodeToString()
        val (page, strokes, images) = NotebookSerializer.deserializePage(pageJson)
            .onFailure { return AppResult.Error(it) }

        val errors = ErrorAccumulator()

        // 3. Download embedded images
        val updatedImages = images.map { image ->
            if (!image.uri.isNullOrEmpty()) {
                val filename = extractFilename(image.uri)
                val localFile = File(ensureImagesFolder(), filename)

                if (!localFile.exists()) {
                    client.getFile(
                        SyncPaths.imageFile(notebookId, filename),
                        localFile
                    ).onSuccess {
                        log.i(TAG, "Downloaded image: $filename")
                    }.onError { error -> addMediaError(errors, "image $filename", error) }
                }
                image.copy(uri = localFile.absolutePath)
            } else {
                image
            }
        }

        // 4. Download page background.
        if (page.backgroundType != "native" && page.background != "blank") {
            val filename = page.background
            // Linked external PDFs live outside managed storage (absolute path); they can't be synced
            // -- skip them entirely, and don't treat their absence as a failure.
            if (File(filename).isAbsolute) {
                log.i(TAG, "Skipping external background (not in managed storage): $filename")
            } else if (filename in attemptedBackgrounds) {
                // A background shared by many pages is only fetched once per notebook.
            } else {
                attemptedBackgrounds.add(filename)
                val localFile = File(ensureBackgroundsFolder(), filename)
                if (!localFile.exists()) {
                    localFile.parentFile?.mkdirs()
                    // Remote path uses the basename (flat), matching how upload stores it.
                    val remoteName = File(filename).name
                    client.getFile(
                        SyncPaths.backgroundFile(notebookId, remoteName),
                        localFile
                    ).onSuccess {
                        log.i(TAG, "Downloaded background: $filename")
                    }.onError { error -> addMediaError(errors, "background $filename", error) }
                }
            }
        }

        // 5. Persist the page atomically: delete-old + update + insert-new run in one transaction,
        //    so a crash can't leave the page with old strokes gone and new ones not yet written.
        try {
            appRepository.replaceDownloadedPage(page, strokes, updatedImages)
        } catch (e: Exception) {
            errors.add(DomainError.DatabaseError("Failed to save page $pageId: ${e.message}"))
        }

        // 6. Return aggregated result, carrying the page's updatedAt as its sync anchor.
        return errors.asResult(page.updatedAt)
    }

    /**
     * Delete remote page/image/background files that the just-committed manifest no longer
     * references. The manifest is authoritative (we just wrote it), so anything not referenced is a
     * true orphan from a deleted or replaced page. Best-effort: every failure is logged, never fatal.
     */
    private suspend fun garbageCollectRemote(notebook: Notebook, client: WebDAVClient) {
        val notebookId = notebook.id
        val referencedPageFiles = notebook.pageIds.map { "$it.json" }.toSet()
        val referencedImages = mutableSetOf<String>()
        val referencedBackgrounds = mutableSetOf<String>()
        for (pageId in notebook.pageIds) {
            // GC only needs the page's media filenames — load the page metadata + image URIs, NOT
            // getWithDataById (which loads and normalizes every stroke on the page). On a large
            // notebook the old call re-materialised the whole notebook right after upload and
            // re-triggered the OOM.
            val page = appRepository.pageRepository.getById(pageId) ?: continue
            appRepository.imageRepository.getUrisForPage(pageId).forEach { uri ->
                if (!uri.isNullOrEmpty()) referencedImages.add(File(uri).name)
            }
            if (page.backgroundType != "native" && page.background != "blank") {
                referencedBackgrounds.add(File(page.background).name)
            }
        }
        pruneRemoteDir(client, SyncPaths.pagesDir(notebookId), referencedPageFiles)
        pruneRemoteDir(client, SyncPaths.imagesDir(notebookId), referencedImages)
        pruneRemoteDir(client, SyncPaths.backgroundsDir(notebookId), referencedBackgrounds)
    }

    /** Delete entries of [dirPath] whose name is not in [keep]. Best-effort. */
    private suspend fun pruneRemoteDir(
        client: WebDAVClient,
        dirPath: String,
        keep: Set<String>
    ) {
        client.listNames(dirPath).onSuccess { names ->
            for (name in names.filter { it !in keep }) {
                client.delete("$dirPath/$name").onSuccess {
                    log.i(TAG, "GC: removed orphan $dirPath/$name")
                }.onError { log.w(TAG, "GC: failed to remove $name: ${it.userMessage}") }
            }
        }.onError { log.w(TAG, "GC: listing $dirPath failed: ${it.userMessage}") }
    }

    /** Delete local pages of [notebook] whose ids left the downloaded manifest. Best-effort. */
    private suspend fun pruneLocalOrphanPages(notebook: Notebook) {
        val keep = notebook.pageIds.toSet()
        val localPageIds = appRepository.pageRepository.getPageIdsForNotebook(notebook.id)
        for (orphan in localPageIds.filter { it !in keep }) {
            try {
                appRepository.pageRepository.delete(orphan)
                log.i(TAG, "GC: removed local orphan page $orphan")
            } catch (e: Exception) {
                log.w(TAG, "GC: failed to delete local page $orphan: ${e.message}")
            }
        }
    }

    /**
     * Aggregate a media (image/background) download failure. A [DomainError.RemoteMissing] (404) is
     * a *permanent* absence — log and skip it so one missing media file can't wedge the whole
     * notebook download into an endless retry. Transient errors are still aggregated so
     * the notebook keeps its stale timestamp and retries next sync.
     */
    private fun addMediaError(errors: ErrorAccumulator, what: String, error: DomainError) {
        if (error is DomainError.RemoteMissing) {
            log.w(TAG, "Media missing on server, skipping $what")
        } else {
            log.e(TAG, "Failed to download $what: ${error.userMessage}")
            errors.add(error)
        }
    }

    private fun extractFilename(uri: String): String = uri.substringAfterLast('/')

    /** Resolve a stored media URI to a [File], tolerating a `file://` scheme. */
    private fun resolveLocalFile(uri: String): File =
        if (uri.startsWith("file:")) File(Uri.parse(uri).path ?: uri) else File(uri)

    private fun detectMimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "NotebookSyncService"
    }
}

/** Pure capability gate kept separate so server-key mismatch behavior is pinned by unit tests. */
internal fun canReuseMovedEtag(
    capabilities: ServerCapabilities?,
    clientServerKey: String
): Boolean = capabilities?.movePreservesEtag == true &&
    capabilities.serverKey == clientServerKey
