package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Alias for cleaner code
private val SLog = SyncLogger

/**
 * Core sync engine orchestrating WebDAV synchronization.
 * Handles bidirectional sync of folders, notebooks, pages, and files.
 */
class SyncEngine(private val context: Context) {

    private val appRepository = AppRepository(context)
    private val kvProxy = KvProxy(context)
    private val credentialManager = CredentialManager(context)
    private val folderSerializer = FolderSerializer
    private val notebookSerializer = NotebookSerializer(context)

    /**
     * Sync all notebooks and folders with the WebDAV server.
     * @return SyncResult indicating success or failure
     */
    suspend fun syncAllNotebooks(): SyncResult = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            SLog.w(TAG, "Sync already in progress, skipping")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }

        val startTime = System.currentTimeMillis()
        var notebooksSynced: Int
        var notebooksDownloaded: Int
        var notebooksDeleted: Int

        return@withContext try {
            SLog.i(TAG, "Starting full sync...")
            updateState(SyncState.Syncing(
                currentStep = SyncStep.INITIALIZING,
                progress = PROGRESS_INITIALIZING,
                details = "Initializing sync..."
            ))

            val initResult = initializeSyncClient()
            if (initResult == null) {
                updateState(SyncState.Error(
                    error = SyncError.CONFIG_ERROR,
                    step = SyncStep.INITIALIZING,
                    canRetry = false
                ))
                return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)
            }
            val (settings, webdavClient) = initResult

            if (settings.syncSettings.wifiOnly && !ConnectivityChecker(context).isUnmeteredConnected()) {
                SLog.i(TAG, "WiFi-only sync enabled but not on WiFi, skipping")
                updateState(SyncState.Error(
                    error = SyncError.WIFI_REQUIRED,
                    step = SyncStep.INITIALIZING,
                    canRetry = false
                ))
                return@withContext SyncResult.Failure(SyncError.WIFI_REQUIRED)
            }

            val skewMs = checkClockSkew(webdavClient)
            if (skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
                val skewSec = skewMs / 1000
                SLog.w(TAG, "Clock skew too large: ${skewSec}s (threshold: ${CLOCK_SKEW_THRESHOLD_MS / 1000}s)")
                updateState(SyncState.Error(
                    error = SyncError.CLOCK_SKEW,
                    step = SyncStep.INITIALIZING,
                    canRetry = false
                ))
                return@withContext SyncResult.Failure(SyncError.CLOCK_SKEW)
            }

            ensureServerDirectories(webdavClient)

            // 1. Sync folders first (they're referenced by notebooks)
            updateState(SyncState.Syncing(
                currentStep = SyncStep.SYNCING_FOLDERS,
                progress = PROGRESS_SYNCING_FOLDERS,
                details = "Syncing folders..."
            ))
            syncFolders(webdavClient)

            // 2. Apply remote deletions (delete local notebooks that were deleted on other devices)
            updateState(SyncState.Syncing(
                currentStep = SyncStep.APPLYING_DELETIONS,
                progress = PROGRESS_APPLYING_DELETIONS,
                details = "Applying remote deletions..."
            ))
            val tombstonedIds = applyRemoteDeletions(webdavClient)

            // 3. Sync existing local notebooks and capture pre-download snapshot
            updateState(SyncState.Syncing(
                currentStep = SyncStep.SYNCING_NOTEBOOKS,
                progress = PROGRESS_SYNCING_NOTEBOOKS,
                details = "Syncing local notebooks..."
            ))
            val preDownloadNotebookIds = syncExistingNotebooks()
            notebooksSynced = preDownloadNotebookIds.size

            // 4. Discover and download new notebooks from server
            updateState(SyncState.Syncing(
                currentStep = SyncStep.DOWNLOADING_NEW,
                progress = PROGRESS_DOWNLOADING_NEW,
                details = "Downloading new notebooks..."
            ))
            val newCount = downloadNewNotebooks(webdavClient, tombstonedIds, settings, preDownloadNotebookIds)
            notebooksDownloaded = newCount

            // 5. Detect local deletions and upload tombstones to server
            updateState(SyncState.Syncing(
                currentStep = SyncStep.UPLOADING_DELETIONS,
                progress = PROGRESS_UPLOADING_DELETIONS,
                details = "Uploading deletions..."
            ))
            val deletedCount = detectAndUploadLocalDeletions(webdavClient, settings, preDownloadNotebookIds)
            notebooksDeleted = deletedCount

            // 6. Update synced notebook IDs for next sync
            updateState(SyncState.Syncing(
                currentStep = SyncStep.FINALIZING,
                progress = PROGRESS_FINALIZING,
                details = "Finalizing..."
            ))
            updateSyncedNotebookIds(settings)

            val duration = System.currentTimeMillis() - startTime
            val summary = SyncSummary(
                notebooksSynced = notebooksSynced,
                notebooksDownloaded = notebooksDownloaded,
                notebooksDeleted = notebooksDeleted,
                duration = duration
            )

            SLog.i(TAG, "✓ Full sync completed in ${duration}ms")
            updateState(SyncState.Success(summary))

            delay(SUCCESS_STATE_AUTO_RESET_MS)
            if (syncState.value is SyncState.Success) {
                updateState(SyncState.Idle)
            }

            SyncResult.Success
        } catch (e: IOException) {
            SLog.e(TAG, "Network error during sync: ${e.message}")
            val currentStep = (syncState.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
            updateState(SyncState.Error(
                error = SyncError.NETWORK_ERROR,
                step = currentStep,
                canRetry = true
            ))
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            SLog.e(TAG, "Unexpected error during sync: ${e.message}\n${e.stackTraceToString()}")
            val currentStep = (syncState.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
            updateState(SyncState.Error(
                error = SyncError.UNKNOWN_ERROR,
                step = currentStep,
                canRetry = false
            ))
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Sync a single notebook with the WebDAV server.
     * Called on note open/close. If a full sync is already running, this is silently skipped
     * to avoid concurrent WebDAV operations. A proper per-notebook mutex would be more correct
     * but is overkill for the single-user use case.
     * @param notebookId Notebook ID to sync
     * @return SyncResult indicating success or failure
     */
    suspend fun syncNotebook(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
        // Skip if a full sync is already holding the mutex
        if (syncMutex.isLocked) {
            SLog.i(TAG, "Full sync in progress, skipping per-notebook sync for $notebookId")
            return@withContext SyncResult.Success
        }
        return@withContext syncNotebookImpl(notebookId)
    }

    /**
     * Internal notebook sync — does not check the mutex. Called from both
     * the public [syncNotebook] entry point and [syncExistingNotebooks] (which
     * already runs inside the full-sync mutex context).
     */
    private fun syncNotebookImpl(notebookId: String): SyncResult {
        return try {
            SLog.i(TAG, "Syncing notebook: $notebookId")

            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return SyncResult.Failure(SyncError.CONFIG_ERROR)

            if (!settings.syncSettings.syncEnabled) {
                return SyncResult.Success
            }

            if (settings.syncSettings.wifiOnly && !ConnectivityChecker(context).isUnmeteredConnected()) {
                SLog.i(TAG, "WiFi-only sync enabled but not on WiFi, skipping notebook sync")
                return SyncResult.Success
            }

            val credentials = credentialManager.getCredentials()
                ?: return SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            val skewMs = checkClockSkew(webdavClient)
            if (skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
                val skewSec = skewMs / 1000
                SLog.w(TAG, "Clock skew too large for single-notebook sync: ${skewSec}s")
                return SyncResult.Failure(SyncError.CLOCK_SKEW)
            }

            val localNotebook = appRepository.bookRepository.getById(notebookId)
                ?: return SyncResult.Failure(SyncError.UNKNOWN_ERROR)

            val remotePath = SyncPaths.manifestFile(notebookId)
            val remoteExists = webdavClient.exists(remotePath)

            SLog.i(TAG, "Checking: ${localNotebook.title}")

            if (remoteExists) {
                val remoteManifestJson = webdavClient.getFile(remotePath).decodeToString()
                val remoteUpdatedAt = notebookSerializer.getManifestUpdatedAt(remoteManifestJson)

                val diffMs = remoteUpdatedAt?.let { localNotebook.updatedAt.time - it.time } ?: Long.MAX_VALUE
                SLog.i(TAG, "Remote: $remoteUpdatedAt (${remoteUpdatedAt?.time}ms)")
                SLog.i(TAG, "Local: ${localNotebook.updatedAt} (${localNotebook.updatedAt.time}ms)")
                SLog.i(TAG, "Difference: ${diffMs}ms")

                when {
                    remoteUpdatedAt == null -> {
                        SLog.i(TAG, "↑ No remote timestamp, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    diffMs < -TIMESTAMP_TOLERANCE_MS -> {
                        SLog.i(TAG, "↓ Remote newer, downloading ${localNotebook.title}")
                        downloadNotebook(notebookId, webdavClient)
                    }
                    diffMs > TIMESTAMP_TOLERANCE_MS -> {
                        SLog.i(TAG, "↑ Local newer, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    else -> {
                        SLog.i(TAG, "= No changes (within tolerance), skipping ${localNotebook.title}")
                    }
                }
            } else {
                SLog.i(TAG, "↑ New on server, uploading ${localNotebook.title}")
                uploadNotebook(localNotebook, webdavClient)
            }

            SLog.i(TAG, "✓ Synced: ${localNotebook.title}")
            SyncResult.Success
        } catch (e: IOException) {
            SLog.e(TAG, "Network error syncing notebook $notebookId: ${e.message}")
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            SLog.e(TAG, "Error syncing notebook $notebookId: ${e.message}\n${e.stackTraceToString()}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Upload a notebook deletion to the server via a zero-byte tombstone file.
     * More efficient than full sync when you just deleted one notebook.
     *
     * Tombstone approach: PUT an empty file at SyncPaths.tombstone(notebookId).
     * This replaces the old deletions.json aggregation file, eliminating the
     * write-write race condition where two devices could overwrite each other.
     * The server's own lastModified on the tombstone provides the deletion timestamp
     * for conflict resolution on other devices.
     *
     * TODO: When ETag support is added, tombstones can be deprecated in favour of
     * detecting deletions via known-ETag + missing remote file (RFC 2518 §9.4).
     *
     * @param notebookId ID of the notebook that was deleted locally
     * @return SyncResult indicating success or failure
     */
    suspend fun uploadDeletion(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            SLog.i(TAG, "Uploading deletion for notebook: $notebookId")

            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            if (!settings.syncSettings.syncEnabled) {
                return@withContext SyncResult.Success
            }

            // Respect wifiOnly - uploading a tombstone is still a network operation
            if (settings.syncSettings.wifiOnly && !ConnectivityChecker(context).isUnmeteredConnected()) {
                SLog.i(TAG, "WiFi-only sync enabled, deferring deletion upload to next WiFi sync")
                return@withContext SyncResult.Success
            }

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            // Delete notebook content from server
            val notebookPath = SyncPaths.notebookDir(notebookId)
            if (webdavClient.exists(notebookPath)) {
                SLog.i(TAG, "✗ Deleting notebook content from server: $notebookId")
                webdavClient.delete(notebookPath)
            }

            // Upload zero-byte tombstone file
            webdavClient.putFile(SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream")
            SLog.i(TAG, "✓ Tombstone uploaded for: $notebookId")

            // Update syncedNotebookIds (remove the deleted notebook)
            val updatedSyncedIds = settings.syncSettings.syncedNotebookIds - notebookId
            kvProxy.setAppSettings(
                settings.copy(
                    syncSettings = settings.syncSettings.copy(
                        syncedNotebookIds = updatedSyncedIds
                    )
                )
            )

            SLog.i(TAG, "✓ Deletion uploaded successfully")
            SyncResult.Success

        } catch (e: Exception) {
            SLog.e(TAG, "Failed to upload deletion: ${e.message}\n${e.stackTraceToString()}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Sync folder hierarchy with the WebDAV server.
     *
     * Note: folders.json is a shared aggregation file and has the same theoretical
     * write-write race condition as the old deletions.json. This is documented but
     * deferred until ETag (If-Match) support is added, at which point server-enforced
     * atomic writes will make this robust.
     */
    private fun syncFolders(webdavClient: WebDAVClient) {
        SLog.i(TAG, "Syncing folders...")

        try {
            val localFolders = appRepository.folderRepository.getAll()

            val remotePath = SyncPaths.foldersFile()
            if (webdavClient.exists(remotePath)) {
                val remoteFoldersJson = webdavClient.getFile(remotePath).decodeToString()
                val remoteFolders = folderSerializer.deserializeFolders(remoteFoldersJson)

                val folderMap = mutableMapOf<String, Folder>()
                remoteFolders.forEach { folderMap[it.id] = it }
                localFolders.forEach { local ->
                    val remote = folderMap[local.id]
                    if (remote == null || local.updatedAt.after(remote.updatedAt)) {
                        folderMap[local.id] = local
                    }
                }

                val mergedFolders = folderMap.values.toList()
                for (folder in mergedFolders) {
                    try {
                        appRepository.folderRepository.get(folder.id)
                        appRepository.folderRepository.update(folder)
                    } catch (_: Exception) {
                        appRepository.folderRepository.create(folder)
                    }
                }

                val updatedFoldersJson = folderSerializer.serializeFolders(mergedFolders)
                webdavClient.putFile(remotePath, updatedFoldersJson.toByteArray(), "application/json")
                SLog.i(TAG, "Synced ${mergedFolders.size} folders")
            } else {
                if (localFolders.isNotEmpty()) {
                    val foldersJson = folderSerializer.serializeFolders(localFolders)
                    webdavClient.putFile(remotePath, foldersJson.toByteArray(), "application/json")
                    SLog.i(TAG, "Uploaded ${localFolders.size} folders to server")
                }
            }
        } catch (e: Exception) {
            SLog.e(TAG, "Error syncing folders: ${e.message}\n${e.stackTraceToString()}")
            throw e
        }
    }

    /**
     * Check for tombstone files on the server and delete any local notebooks that were
     * deleted on other devices.
     *
     * Tombstones are zero-byte files at [SyncPaths.tombstone]. The server's lastModified
     * on each tombstone provides the deletion timestamp for conflict resolution: if a local
     * notebook was modified AFTER the tombstone was placed, it is treated as a resurrection
     * and will be re-uploaded (overwriting the tombstone on the next full sync).
     *
     * @return Set of tombstoned notebook IDs (used to filter discovery in [downloadNewNotebooks])
     */
    private fun applyRemoteDeletions(webdavClient: WebDAVClient): Set<String> {
        SLog.i(TAG, "Applying remote deletions...")

        val tombstonesPath = SyncPaths.tombstonesDir()
        if (!webdavClient.exists(tombstonesPath)) return emptySet()

        val tombstones = webdavClient.listCollectionWithMetadata(tombstonesPath)
        val tombstonedIds = tombstones.map { it.name }.toSet()

        if (tombstones.isNotEmpty()) {
            SLog.i(TAG, "Server has ${tombstones.size} tombstone(s)")
            for (tombstone in tombstones) {
                val notebookId = tombstone.name
                val deletedAt = tombstone.lastModified

                val localNotebook = appRepository.bookRepository.getById(notebookId) ?: continue

                // Conflict resolution: local modified AFTER tombstone → resurrection
                if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) {
                    SLog.i(TAG, "↻ Resurrecting '${localNotebook.title}' (modified after server deletion)")
                    continue
                }

                try {
                    SLog.i(TAG, "✗ Deleting locally (tombstone on server): ${localNotebook.title}")
                    appRepository.bookRepository.delete(notebookId)
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                }
            }
        }

        // Prune stale tombstones. Safe to do after processing — the current device has
        // already applied all deletions, so old tombstones are no longer needed by us.
        // Devices that haven't synced in TOMBSTONE_MAX_AGE_DAYS need full reconciliation anyway.
        val cutoff = java.util.Date(System.currentTimeMillis() - TOMBSTONE_MAX_AGE_DAYS * 86_400_000L)
        val stale = tombstones.filter { it.lastModified != null && it.lastModified.before(cutoff) }
        if (stale.isNotEmpty()) {
            SLog.i(TAG, "Pruning ${stale.size} stale tombstone(s) older than $TOMBSTONE_MAX_AGE_DAYS days")
            for (entry in stale) {
                try {
                    webdavClient.delete(SyncPaths.tombstone(entry.name))
                } catch (e: Exception) {
                    SLog.w(TAG, "Failed to prune tombstone ${entry.name}: ${e.message}")
                }
            }
        }

        return tombstonedIds
    }

    /**
     * Detect notebooks that were deleted locally and upload tombstone files to server.
     * @param preDownloadNotebookIds Snapshot of local notebook IDs BEFORE downloading new notebooks.
     * @return Number of notebooks deleted
     */
    private fun detectAndUploadLocalDeletions(
        webdavClient: WebDAVClient,
        settings: AppSettings,
        preDownloadNotebookIds: Set<String>
    ): Int {
        SLog.i(TAG, "Detecting local deletions...")

        val syncedNotebookIds = settings.syncSettings.syncedNotebookIds
        val deletedLocally = syncedNotebookIds - preDownloadNotebookIds

        if (deletedLocally.isNotEmpty()) {
            SLog.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")

            for (notebookId in deletedLocally) {
                try {
                    val notebookPath = SyncPaths.notebookDir(notebookId)
                    if (webdavClient.exists(notebookPath)) {
                        SLog.i(TAG, "✗ Deleting from server: $notebookId")
                        webdavClient.delete(notebookPath)
                    }

                    // Upload zero-byte tombstone
                    webdavClient.putFile(SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream")
                    SLog.i(TAG, "✓ Tombstone uploaded for: $notebookId")
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to process local deletion $notebookId: ${e.message}")
                }
            }
        } else {
            SLog.i(TAG, "No local deletions detected")
        }

        return deletedLocally.size
    }

    /**
     * Upload a notebook to the WebDAV server.
     */
    private fun uploadNotebook(notebook: Notebook, webdavClient: WebDAVClient) {
        val notebookId = notebook.id
        SLog.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        webdavClient.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/")
        webdavClient.createCollection(SyncPaths.imagesDir(notebookId))
        webdavClient.createCollection(SyncPaths.backgroundsDir(notebookId))

        val manifestJson = notebookSerializer.serializeManifest(notebook)
        webdavClient.putFile(SyncPaths.manifestFile(notebookId), manifestJson.toByteArray(), "application/json")

        val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
        for (page in pages) {
            uploadPage(page, notebookId, webdavClient)
        }

        // If a tombstone exists for this notebook (resurrection case), remove it
        val tombstonePath = SyncPaths.tombstone(notebookId)
        if (webdavClient.exists(tombstonePath)) {
            webdavClient.delete(tombstonePath)
            SLog.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
        }

        SLog.i(TAG, "✓ Uploaded: ${notebook.title}")
    }

    /**
     * Upload a single page with its strokes and images.
     */
    private fun uploadPage(page: Page, notebookId: String, webdavClient: WebDAVClient) {
        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(page.id)
        val pageWithImages = appRepository.pageRepository.getWithImageById(page.id)

        val pageJson = notebookSerializer.serializePage(
            page,
            pageWithStrokes.strokes,
            pageWithImages.images
        )

        webdavClient.putFile(
            SyncPaths.pageFile(notebookId, page.id),
            pageJson.toByteArray(),
            "application/json"
        )

        for (image in pageWithImages.images) {
            if (!image.uri.isNullOrEmpty()) {
                val localFile = File(image.uri)
                if (localFile.exists()) {
                    val remotePath = SyncPaths.imageFile(notebookId, localFile.name)
                    if (!webdavClient.exists(remotePath)) {
                        webdavClient.putFile(remotePath, localFile, detectMimeType(localFile))
                        SLog.i(TAG, "Uploaded image: ${localFile.name}")
                    }
                } else {
                    SLog.w(TAG, "Image file not found: ${image.uri}")
                }
            }
        }

        if (page.backgroundType != "native" && page.background != "blank") {
            val bgFile = File(ensureBackgroundsFolder(), page.background)
            if (bgFile.exists()) {
                val remotePath = SyncPaths.backgroundFile(notebookId, bgFile.name)
                if (!webdavClient.exists(remotePath)) {
                    webdavClient.putFile(remotePath, bgFile, detectMimeType(bgFile))
                    SLog.i(TAG, "Uploaded background: ${bgFile.name}")
                }
            }
        }
    }

    /**
     * Download a notebook from the WebDAV server.
     */
    private fun downloadNotebook(notebookId: String, webdavClient: WebDAVClient) {
        SLog.i(TAG, "Downloading notebook ID: $notebookId")

        val manifestJson = webdavClient.getFile(SyncPaths.manifestFile(notebookId)).decodeToString()
        val notebook = notebookSerializer.deserializeManifest(manifestJson)

        SLog.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

        val existingNotebook = appRepository.bookRepository.getById(notebookId)
        if (existingNotebook != null) {
            appRepository.bookRepository.updatePreservingTimestamp(notebook)
        } else {
            appRepository.bookRepository.createEmpty(notebook)
        }

        for (pageId in notebook.pageIds) {
            try {
                downloadPage(pageId, notebookId, webdavClient)
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to download page $pageId: ${e.message}")
            }
        }

        SLog.i(TAG, "✓ Downloaded: ${notebook.title}")
    }

    /**
     * Download a single page with its strokes and images.
     */
    private fun downloadPage(pageId: String, notebookId: String, webdavClient: WebDAVClient) {
        val pageJson = webdavClient.getFile(SyncPaths.pageFile(notebookId, pageId)).decodeToString()
        val (page, strokes, images) = notebookSerializer.deserializePage(pageJson)

        val updatedImages = images.map { image ->
            if (!image.uri.isNullOrEmpty()) {
                try {
                    val filename = extractFilename(image.uri)
                    val localFile = File(ensureImagesFolder(), filename)

                    if (!localFile.exists()) {
                        webdavClient.getFile(SyncPaths.imageFile(notebookId, filename), localFile)
                        SLog.i(TAG, "Downloaded image: $filename")
                    }

                    image.copy(uri = localFile.absolutePath)
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to download image ${image.uri}: ${e.message}\n${e.stackTraceToString()}")
                    image
                }
            } else {
                image
            }
        }

        if (page.backgroundType != "native" && page.background != "blank") {
            try {
                val filename = page.background
                val localFile = File(ensureBackgroundsFolder(), filename)

                if (!localFile.exists()) {
                    webdavClient.getFile(SyncPaths.backgroundFile(notebookId, filename), localFile)
                    SLog.i(TAG, "Downloaded background: $filename")
                }
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to download background ${page.background}: ${e.message}\n${e.stackTraceToString()}")
            }
        }

        val existingPage = appRepository.pageRepository.getById(page.id)
        if (existingPage != null) {
            val existingStrokes = appRepository.pageRepository.getWithStrokeById(page.id).strokes
            val existingImages = appRepository.pageRepository.getWithImageById(page.id).images

            appRepository.strokeRepository.deleteAll(existingStrokes.map { it.id })
            appRepository.imageRepository.deleteAll(existingImages.map { it.id })

            appRepository.pageRepository.update(page)
        } else {
            appRepository.pageRepository.create(page)
        }

        appRepository.strokeRepository.create(strokes)
        appRepository.imageRepository.create(updatedImages)
    }

    /**
     * Force upload all local data to server (replaces server data).
     * WARNING: This deletes all data on the server first!
     */
    suspend fun forceUploadAll(): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            SLog.i(TAG, "⚠ FORCE UPLOAD: Replacing server with local data")

            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            try {
                if (webdavClient.exists(SyncPaths.notebooksDir())) {
                    val existingNotebooks = webdavClient.listCollection(SyncPaths.notebooksDir())
                    SLog.i(TAG, "Deleting ${existingNotebooks.size} existing notebooks from server")
                    for (notebookDir in existingNotebooks) {
                        try {
                            webdavClient.delete(SyncPaths.notebookDir(notebookDir))
                        } catch (e: Exception) {
                            SLog.w(TAG, "Failed to delete $notebookDir: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                SLog.w(TAG, "Error cleaning server notebooks: ${e.message}")
            }

            if (!webdavClient.exists(SyncPaths.rootDir())) {
                webdavClient.createCollection(SyncPaths.rootDir())
            }
            if (!webdavClient.exists(SyncPaths.notebooksDir())) {
                webdavClient.createCollection(SyncPaths.notebooksDir())
            }
            if (!webdavClient.exists(SyncPaths.tombstonesDir())) {
                webdavClient.createCollection(SyncPaths.tombstonesDir())
            }

            val folders = appRepository.folderRepository.getAll()
            if (folders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(folders)
                webdavClient.putFile(SyncPaths.foldersFile(), foldersJson.toByteArray(), "application/json")
                SLog.i(TAG, "Uploaded ${folders.size} folders")
            }

            val notebooks = appRepository.bookRepository.getAll()
            SLog.i(TAG, "Uploading ${notebooks.size} local notebooks...")
            for (notebook in notebooks) {
                try {
                    uploadNotebook(notebook, webdavClient)
                    SLog.i(TAG, "✓ Uploaded: ${notebook.title}")
                } catch (e: Exception) {
                    SLog.e(TAG, "✗ Failed to upload ${notebook.title}: ${e.message}")
                }
            }

            SLog.i(TAG, "✓ FORCE UPLOAD complete: ${notebooks.size} notebooks")
            SyncResult.Success
        } catch (e: Exception) {
            SLog.e(TAG, "Force upload failed: ${e.message}\n${e.stackTraceToString()}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Force download all server data to local (replaces local data).
     * WARNING: This deletes all local notebooks first!
     */
    suspend fun forceDownloadAll(): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            SLog.i(TAG, "⚠ FORCE DOWNLOAD: Replacing local with server data")

            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            val localFolders = appRepository.folderRepository.getAll()
            for (folder in localFolders) {
                appRepository.folderRepository.delete(folder.id)
            }

            val localNotebooks = appRepository.bookRepository.getAll()
            for (notebook in localNotebooks) {
                appRepository.bookRepository.delete(notebook.id)
            }
            SLog.i(TAG, "Deleted ${localFolders.size} folders and ${localNotebooks.size} local notebooks")

            if (webdavClient.exists(SyncPaths.foldersFile())) {
                val foldersJson = webdavClient.getFile(SyncPaths.foldersFile()).decodeToString()
                val folders = folderSerializer.deserializeFolders(foldersJson)
                for (folder in folders) {
                    appRepository.folderRepository.create(folder)
                }
                SLog.i(TAG, "Downloaded ${folders.size} folders from server")
            }

            if (webdavClient.exists(SyncPaths.notebooksDir())) {
                val notebookDirs = webdavClient.listCollection(SyncPaths.notebooksDir())
                SLog.i(TAG, "Found ${notebookDirs.size} notebook(s) on server")

                for (notebookDir in notebookDirs) {
                    try {
                        val notebookId = notebookDir.trimEnd('/')
                        SLog.i(TAG, "Downloading notebook: $notebookId")
                        downloadNotebook(notebookId, webdavClient)
                    } catch (e: Exception) {
                        SLog.e(TAG, "Failed to download $notebookDir: ${e.message}\n${e.stackTraceToString()}")
                    }
                }
            } else {
                SLog.w(TAG, "${SyncPaths.notebooksDir()} doesn't exist on server")
            }

            SLog.i(TAG, "✓ FORCE DOWNLOAD complete")
            SyncResult.Success
        } catch (e: Exception) {
            SLog.e(TAG, "Force download failed: ${e.message}\n${e.stackTraceToString()}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Extract filename from a URI or path.
     */
    private fun extractFilename(uri: String): String {
        return uri.substringAfterLast('/')
    }

    /**
     * Detect MIME type from file extension.
     */
    private fun detectMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /**
     * Check clock skew between this device and the WebDAV server.
     * @return Skew in ms (deviceTime - serverTime), or null if server time unavailable
     */
    private fun checkClockSkew(webdavClient: WebDAVClient): Long? {
        val serverTime = webdavClient.getServerTime() ?: return null
        return System.currentTimeMillis() - serverTime
    }

    /**
     * Initialize sync client by getting settings and credentials.
     * @return Pair of (AppSettings, WebDAVClient) or null if initialization fails
     */
    private fun initializeSyncClient(): Pair<AppSettings, WebDAVClient>? {
        val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
            ?: return null

        if (!settings.syncSettings.syncEnabled) {
            SLog.i(TAG, "Sync disabled in settings")
            return null
        }

        val credentials = credentialManager.getCredentials()
            ?: return null

        val webdavClient = WebDAVClient(
            settings.syncSettings.serverUrl,
            credentials.first,
            credentials.second
        )

        return Pair(settings, webdavClient)
    }

    /**
     * Ensure required server directory structure exists, and run one-time migration
     * from the old deletions.json format to tombstone files.
     */
    private fun ensureServerDirectories(webdavClient: WebDAVClient) {
        if (!webdavClient.exists(SyncPaths.rootDir())) {
            webdavClient.createCollection(SyncPaths.rootDir())
        }
        if (!webdavClient.exists(SyncPaths.notebooksDir())) {
            webdavClient.createCollection(SyncPaths.notebooksDir())
        }
        if (!webdavClient.exists(SyncPaths.tombstonesDir())) {
            webdavClient.createCollection(SyncPaths.tombstonesDir())
        }
        migrateDeletionsJsonToTombstones(webdavClient)
    }

    /**
     * One-time migration: convert old deletions.json entries to individual tombstone files,
     * then delete the legacy file.
     */
    private fun migrateDeletionsJsonToTombstones(webdavClient: WebDAVClient) {
        if (!webdavClient.exists(LEGACY_DELETIONS_FILE)) return

        try {
            val json = webdavClient.getFile(LEGACY_DELETIONS_FILE).decodeToString()
            val data = DeletionsSerializer.deserialize(json)

            for (notebookId in data.getAllDeletedIds()) {
                val tombstonePath = SyncPaths.tombstone(notebookId)
                if (!webdavClient.exists(tombstonePath)) {
                    webdavClient.putFile(tombstonePath, ByteArray(0), "application/octet-stream")
                }
            }

            webdavClient.delete(LEGACY_DELETIONS_FILE)
            SLog.i(TAG, "Migrated ${data.getAllDeletedIds().size} entries from deletions.json to tombstones")
        } catch (e: Exception) {
            SLog.w(TAG, "Failed to migrate deletions.json: ${e.message}")
        }
    }

    /**
     * Sync all existing local notebooks.
     * @return Set of notebook IDs that existed before any new downloads
     */
    private fun syncExistingNotebooks(): Set<String> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        SLog.i(TAG, "Found ${localNotebooks.size} local notebooks")

        for (notebook in localNotebooks) {
            try {
                syncNotebookImpl(notebook.id)
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to sync ${notebook.title}: ${e.message}")
            }
        }

        return preDownloadNotebookIds
    }

    /**
     * Discover and download new notebooks from server that don't exist locally.
     * @param tombstonedIds Notebook IDs that have tombstones — skip these, they were intentionally deleted
     * @return Number of notebooks downloaded
     */
    private fun downloadNewNotebooks(
        webdavClient: WebDAVClient,
        tombstonedIds: Set<String>,
        settings: AppSettings,
        preDownloadNotebookIds: Set<String>
    ): Int {
        SLog.i(TAG, "Checking server for new notebooks...")

        if (!webdavClient.exists(SyncPaths.notebooksDir())) {
            return 0
        }

        val serverNotebookDirs = webdavClient.listCollection(SyncPaths.notebooksDir())

        val newNotebookIds = serverNotebookDirs
            .map { it.trimEnd('/') }
            .filter { it !in preDownloadNotebookIds }
            .filter { it !in tombstonedIds }
            .filter { it !in settings.syncSettings.syncedNotebookIds }

        if (newNotebookIds.isNotEmpty()) {
            SLog.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
            for (notebookId in newNotebookIds) {
                try {
                    SLog.i(TAG, "↓ Downloading new notebook from server: $notebookId")
                    downloadNotebook(notebookId, webdavClient)
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to download $notebookId: ${e.message}")
                }
            }
        } else {
            SLog.i(TAG, "No new notebooks on server")
        }

        return newNotebookIds.size
    }

    /**
     * Update the list of synced notebook IDs in settings.
     */
    private fun updateSyncedNotebookIds(settings: AppSettings) {
        val currentNotebookIds = appRepository.bookRepository.getAll().map { it.id }.toSet()
        kvProxy.setAppSettings(
            settings.copy(
                syncSettings = settings.syncSettings.copy(
                    syncedNotebookIds = currentNotebookIds
                )
            )
        )
    }

    companion object {
        private const val TAG = "SyncEngine"

        // Path to the legacy deletions.json file, used only for one-time migration
        private const val LEGACY_DELETIONS_FILE = "/notable/deletions.json"

        // Progress percentages for each sync step
        private const val PROGRESS_INITIALIZING = 0.0f
        private const val PROGRESS_SYNCING_FOLDERS = 0.1f
        private const val PROGRESS_APPLYING_DELETIONS = 0.2f
        private const val PROGRESS_SYNCING_NOTEBOOKS = 0.3f
        private const val PROGRESS_DOWNLOADING_NEW = 0.6f
        private const val PROGRESS_UPLOADING_DELETIONS = 0.8f
        private const val PROGRESS_FINALIZING = 0.9f

        // Timing constants
        private const val SUCCESS_STATE_AUTO_RESET_MS = 3000L
        private const val TIMESTAMP_TOLERANCE_MS = 1000L
        private const val CLOCK_SKEW_THRESHOLD_MS = 30_000L

        // Tombstones older than this are pruned at the end of applyRemoteDeletions().
        // Any device that hasn't synced in this long will need full reconciliation anyway.
        private const val TOMBSTONE_MAX_AGE_DAYS = 90L

        // Shared state across all SyncEngine instances
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        // Mutex to prevent concurrent full syncs
        private val syncMutex = Mutex()

        /**
         * Update the sync state (internal use only).
         */
        internal fun updateState(state: SyncState) {
            _syncState.value = state
        }
    }
}

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    object Success : SyncResult()
    data class Failure(val error: SyncError) : SyncResult()
}

/**
 * Types of sync errors.
 */
enum class SyncError {
    NETWORK_ERROR,
    AUTH_ERROR,
    CONFIG_ERROR,
    CLOCK_SKEW,
    WIFI_REQUIRED,
    SYNC_IN_PROGRESS,
    UNKNOWN_ERROR
}

/**
 * Represents the current state of a sync operation.
 */
sealed class SyncState {
    object Idle : SyncState()

    data class Syncing(
        val currentStep: SyncStep,
        val progress: Float,
        val details: String
    ) : SyncState()

    data class Success(
        val summary: SyncSummary
    ) : SyncState()

    data class Error(
        val error: SyncError,
        val step: SyncStep,
        val canRetry: Boolean
    ) : SyncState()
}

/**
 * Steps in the sync process, used for progress tracking.
 */
enum class SyncStep {
    INITIALIZING,
    SYNCING_FOLDERS,
    APPLYING_DELETIONS,
    SYNCING_NOTEBOOKS,
    DOWNLOADING_NEW,
    UPLOADING_DELETIONS,
    FINALIZING
}

/**
 * Summary of a completed sync operation.
 */
data class SyncSummary(
    val notebooksSynced: Int,
    val notebooksDownloaded: Int,
    val notebooksDeleted: Int,
    val duration: Long
)
