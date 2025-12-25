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
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date

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
        // Try to acquire mutex - fail fast if already syncing
        if (!syncMutex.tryLock()) {
            SLog.w(TAG, "Sync already in progress, skipping")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }

        val startTime = System.currentTimeMillis()
        var notebooksSynced = 0
        var notebooksDownloaded = 0
        var notebooksDeleted = 0

        return@withContext try {
            SLog.i(TAG, "Starting full sync...")
            updateState(SyncState.Syncing(
                currentStep = SyncStep.INITIALIZING,
                progress = PROGRESS_INITIALIZING,
                details = "Initializing sync..."
            ))

            // Initialize sync client and settings
            val (settings, webdavClient) = initializeSyncClient()
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            // Ensure base directory structure exists on server
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
            val deletionsData = applyRemoteDeletions(webdavClient)

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
            val newCount = downloadNewNotebooks(webdavClient, deletionsData, settings, preDownloadNotebookIds)
            notebooksDownloaded = newCount

            // 5. Detect local deletions and upload to server
            updateState(SyncState.Syncing(
                currentStep = SyncStep.UPLOADING_DELETIONS,
                progress = PROGRESS_UPLOADING_DELETIONS,
                details = "Uploading deletions..."
            ))
            val deletedCount = detectAndUploadLocalDeletions(webdavClient, settings, preDownloadNotebookIds)
            notebooksDeleted = deletedCount

            // 6. Sync Quick Pages (pages with notebookId = null)
            // TODO: Implement Quick Pages sync

            // 7. Update synced notebook IDs for next sync
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

            // Auto-reset to Idle after a delay
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
            SLog.e(TAG, "Unexpected error during sync: ${e.message}")
            e.printStackTrace()
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
     * @param notebookId Notebook ID to sync
     * @return SyncResult indicating success or failure
     */
    suspend fun syncNotebook(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            SLog.i(TAG, "Syncing notebook: $notebookId")

            // Get sync settings and credentials
            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            if (!settings.syncSettings.syncEnabled) {
                return@withContext SyncResult.Success
            }

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            // Get local notebook
            val localNotebook = appRepository.bookRepository.getById(notebookId)
                ?: return@withContext SyncResult.Failure(SyncError.UNKNOWN_ERROR)

            // Check if remote notebook exists
            val remotePath = "/Notable/notebooks/$notebookId/manifest.json"
            val remoteExists = webdavClient.exists(remotePath)

            SLog.i(TAG, "Checking: ${localNotebook.title}")

            if (remoteExists) {
                // Fetch remote manifest and compare timestamps
                val remoteManifestJson = webdavClient.getFile(remotePath).decodeToString()
                val remoteUpdatedAt = notebookSerializer.getManifestUpdatedAt(remoteManifestJson)

                val diffMs = remoteUpdatedAt?.let { localNotebook.updatedAt.time - it.time } ?: Long.MAX_VALUE
                SLog.i(TAG, "Remote: $remoteUpdatedAt (${remoteUpdatedAt?.time}ms)")
                SLog.i(TAG, "Local: ${localNotebook.updatedAt} (${localNotebook.updatedAt.time}ms)")
                SLog.i(TAG, "Difference: ${diffMs}ms")

                // Use tolerance to ignore millisecond precision differences
                when {
                    remoteUpdatedAt == null -> {
                        SLog.i(TAG, "↑ No remote timestamp, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    diffMs < -TIMESTAMP_TOLERANCE_MS -> {
                        // Remote is newer by more than tolerance - download
                        SLog.i(TAG, "↓ Remote newer, downloading ${localNotebook.title}")
                        downloadNotebook(notebookId, webdavClient)
                    }
                    diffMs > TIMESTAMP_TOLERANCE_MS -> {
                        // Local is newer by more than tolerance - upload
                        SLog.i(TAG, "↑ Local newer, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    else -> {
                        // Within tolerance - no significant change
                        SLog.i(TAG, "= No changes (within tolerance), skipping ${localNotebook.title}")
                    }
                }
            } else {
                // Remote doesn't exist - upload
                SLog.i(TAG, "↑ New on server, uploading ${localNotebook.title}")
                uploadNotebook(localNotebook, webdavClient)
            }

            SLog.i(TAG, "✓ Synced: ${localNotebook.title}")
            SyncResult.Success
        } catch (e: IOException) {
            Log.e(TAG, "Network error syncing notebook $notebookId: ${e.message}")
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing notebook $notebookId: ${e.message}")
            e.printStackTrace()
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Upload a notebook deletion to the server.
     * More efficient than full sync when you just deleted one notebook.
     * @param notebookId ID of the notebook that was deleted locally
     * @return SyncResult indicating success or failure
     */
    suspend fun uploadDeletion(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            SLog.i(TAG, "Uploading deletion for notebook: $notebookId")

            // Get sync settings and credentials
            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            if (!settings.syncSettings.syncEnabled) {
                return@withContext SyncResult.Success
            }

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            // Read current deletions.json from server
            val remotePath = "/Notable/deletions.json"
            val deletionsSerializer = DeletionsSerializer
            var deletionsData = if (webdavClient.exists(remotePath)) {
                try {
                    val deletionsJson = webdavClient.getFile(remotePath).decodeToString()
                    deletionsSerializer.deserialize(deletionsJson)
                } catch (e: Exception) {
                    SLog.w(TAG, "Failed to parse deletions.json: ${e.message}")
                    DeletionsData()
                }
            } else {
                DeletionsData()
            }

            // Add this notebook to deletions
            deletionsData = deletionsData.copy(
                deletedNotebookIds = deletionsData.deletedNotebookIds + notebookId
            )

            // Delete notebook directory from server
            val notebookPath = "/Notable/notebooks/$notebookId"
            if (webdavClient.exists(notebookPath)) {
                SLog.i(TAG, "✗ Deleting from server: $notebookId")
                webdavClient.delete(notebookPath)
            }

            // Upload updated deletions.json
            val deletionsJson = deletionsSerializer.serialize(deletionsData)
            webdavClient.putFile(remotePath, deletionsJson.toByteArray(), "application/json")
            SLog.i(TAG, "Updated deletions.json on server")

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
            SLog.e(TAG, "Failed to upload deletion: ${e.message}")
            e.printStackTrace()
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    /**
     * Sync folder hierarchy with the WebDAV server.
     */
    private suspend fun syncFolders(webdavClient: WebDAVClient) {
        Log.i(TAG, "Syncing folders...")

        try {
            // Get local folders
            val localFolders = appRepository.folderRepository.getAll()

            // Check if remote folders.json exists
            val remotePath = "/Notable/folders.json"
            if (webdavClient.exists(remotePath)) {
                // Download and merge
                val remoteFoldersJson = webdavClient.getFile(remotePath).decodeToString()
                val remoteFolders = folderSerializer.deserializeFolders(remoteFoldersJson)

                // Simple merge: take newer version of each folder
                val folderMap = mutableMapOf<String, Folder>()

                // Add all remote folders
                remoteFolders.forEach { folderMap[it.id] = it }

                // Merge with local folders (take newer based on updatedAt)
                localFolders.forEach { local ->
                    val remote = folderMap[local.id]
                    if (remote == null || local.updatedAt.after(remote.updatedAt)) {
                        folderMap[local.id] = local
                    }
                }

                // Update local database with merged folders
                val mergedFolders = folderMap.values.toList()
                for (folder in mergedFolders) {
                    try {
                        appRepository.folderRepository.get(folder.id)
                        // Folder exists, update it
                        appRepository.folderRepository.update(folder)
                    } catch (e: Exception) {
                        // Folder doesn't exist, create it
                        appRepository.folderRepository.create(folder)
                    }
                }

                // Upload merged folders back to server
                val updatedFoldersJson = folderSerializer.serializeFolders(mergedFolders)
                webdavClient.putFile(remotePath, updatedFoldersJson.toByteArray(), "application/json")
                Log.i(TAG, "Synced ${mergedFolders.size} folders")
            } else {
                // Remote doesn't exist - upload local folders
                if (localFolders.isNotEmpty()) {
                    val foldersJson = folderSerializer.serializeFolders(localFolders)
                    webdavClient.putFile(remotePath, foldersJson.toByteArray(), "application/json")
                    Log.i(TAG, "Uploaded ${localFolders.size} folders to server")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing folders: ${e.message}")
            throw e
        }
    }

    /**
     * Download deletions.json from server and delete any local notebooks that were deleted on other devices.
     * This should be called EARLY in the sync process, before uploading local notebooks.
     * @return DeletionsData for filtering discovery
     */
    private suspend fun applyRemoteDeletions(webdavClient: WebDAVClient): DeletionsData {
        SLog.i(TAG, "Applying remote deletions...")

        val remotePath = "/Notable/deletions.json"
        val deletionsSerializer = DeletionsSerializer

        // Download deletions.json from server (if it exists)
        val deletionsData = if (webdavClient.exists(remotePath)) {
            try {
                val deletionsJson = webdavClient.getFile(remotePath).decodeToString()
                deletionsSerializer.deserialize(deletionsJson)
            } catch (e: Exception) {
                SLog.w(TAG, "Failed to parse deletions.json: ${e.message}")
                DeletionsData()
            }
        } else {
            DeletionsData()
        }

        // Delete any local notebooks that are in the server's deletions list
        if (deletionsData.deletedNotebookIds.isNotEmpty()) {
            SLog.i(TAG, "Server has ${deletionsData.deletedNotebookIds.size} deleted notebook(s)")
            val localNotebooks = appRepository.bookRepository.getAll()
            for (notebook in localNotebooks) {
                if (notebook.id in deletionsData.deletedNotebookIds) {
                    try {
                        SLog.i(TAG, "✗ Deleting locally (deleted on server): ${notebook.title}")
                        appRepository.bookRepository.delete(notebook.id)
                    } catch (e: Exception) {
                        SLog.e(TAG, "Failed to delete ${notebook.title}: ${e.message}")
                    }
                }
            }
        }

        return deletionsData
    }

    /**
     * Detect notebooks that were deleted locally and upload deletions to server.
     * @param preDownloadNotebookIds Snapshot of local notebook IDs BEFORE downloading new notebooks.
     *        This is critical - if we use current state, we can't tell which notebooks were deleted
     *        locally vs. just downloaded from server.
     * @return Number of notebooks deleted
     */
    private suspend fun detectAndUploadLocalDeletions(
        webdavClient: WebDAVClient,
        settings: AppSettings,
        preDownloadNotebookIds: Set<String>
    ): Int {
        SLog.i(TAG, "Detecting local deletions...")

        val remotePath = "/Notable/deletions.json"
        val deletionsSerializer = DeletionsSerializer

        // Get current deletions from server
        var deletionsData = if (webdavClient.exists(remotePath)) {
            try {
                val deletionsJson = webdavClient.getFile(remotePath).decodeToString()
                deletionsSerializer.deserialize(deletionsJson)
            } catch (e: Exception) {
                SLog.w(TAG, "Failed to parse deletions.json: ${e.message}")
                DeletionsData()
            }
        } else {
            DeletionsData()
        }

        // Detect local deletions by comparing with previously synced notebook IDs
        // IMPORTANT: Use the pre-download snapshot, not current state
        val syncedNotebookIds = settings.syncSettings.syncedNotebookIds
        val deletedLocally = syncedNotebookIds - preDownloadNotebookIds

        if (deletedLocally.isNotEmpty()) {
            SLog.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")

            // Add local deletions to the deletions list
            deletionsData = deletionsData.copy(
                deletedNotebookIds = deletionsData.deletedNotebookIds + deletedLocally
            )

            // Delete from server
            for (notebookId in deletedLocally) {
                try {
                    val notebookPath = "/Notable/notebooks/$notebookId"
                    if (webdavClient.exists(notebookPath)) {
                        SLog.i(TAG, "✗ Deleting from server: $notebookId")
                        webdavClient.delete(notebookPath)
                    }
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to delete $notebookId from server: ${e.message}")
                }
            }

            // Upload updated deletions.json
            val deletionsJson = deletionsSerializer.serialize(deletionsData)
            webdavClient.putFile(remotePath, deletionsJson.toByteArray(), "application/json")
            SLog.i(TAG, "Updated deletions.json on server with ${deletionsData.deletedNotebookIds.size} total deletion(s)")
        } else {
            SLog.i(TAG, "No local deletions detected")
        }

        return deletedLocally.size
    }

    /**
     * Upload a notebook to the WebDAV server.
     */
    private suspend fun uploadNotebook(notebook: Notebook, webdavClient: WebDAVClient) {
        val notebookId = notebook.id
        SLog.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        // Create remote directory structure
        webdavClient.ensureParentDirectories("/Notable/notebooks/$notebookId/pages/")
        webdavClient.createCollection("/Notable/notebooks/$notebookId/images")
        webdavClient.createCollection("/Notable/notebooks/$notebookId/backgrounds")

        // Upload manifest.json
        val manifestJson = notebookSerializer.serializeManifest(notebook)
        webdavClient.putFile(
            "/Notable/notebooks/$notebookId/manifest.json",
            manifestJson.toByteArray(),
            "application/json"
        )

        // Upload each page
        val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
        for (page in pages) {
            uploadPage(page, notebookId, webdavClient)
        }

        SLog.i(TAG, "✓ Uploaded: ${notebook.title}")
    }

    /**
     * Upload a single page with its strokes and images.
     */
    private suspend fun uploadPage(page: Page, notebookId: String, webdavClient: WebDAVClient) {
        // Get strokes and images for this page
        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(page.id)
        val pageWithImages = appRepository.pageRepository.getWithImageById(page.id)

        // Serialize page to JSON
        val pageJson = notebookSerializer.serializePage(
            page,
            pageWithStrokes.strokes,
            pageWithImages.images
        )

        // Upload page JSON
        webdavClient.putFile(
            "/Notable/notebooks/$notebookId/pages/${page.id}.json",
            pageJson.toByteArray(),
            "application/json"
        )

        // Upload referenced images
        for (image in pageWithImages.images) {
            if (image.uri != null) {
                val localFile = File(image.uri)
                if (localFile.exists()) {
                    val remotePath = "/Notable/notebooks/$notebookId/images/${localFile.name}"
                    if (!webdavClient.exists(remotePath)) {
                        webdavClient.putFile(remotePath, localFile, detectMimeType(localFile))
                        Log.i(TAG, "Uploaded image: ${localFile.name}")
                    }
                } else {
                    Log.w(TAG, "Image file not found: ${image.uri}")
                }
            }
        }

        // Upload custom backgrounds (skip native templates)
        if (page.backgroundType != "native" && page.background != "blank") {
            val bgFile = File(ensureBackgroundsFolder(), page.background)
            if (bgFile.exists()) {
                val remotePath = "/Notable/notebooks/$notebookId/backgrounds/${bgFile.name}"
                if (!webdavClient.exists(remotePath)) {
                    webdavClient.putFile(remotePath, bgFile, detectMimeType(bgFile))
                    Log.i(TAG, "Uploaded background: ${bgFile.name}")
                }
            }
        }
    }

    /**
     * Download a notebook from the WebDAV server.
     */
    private suspend fun downloadNotebook(notebookId: String, webdavClient: WebDAVClient) {
        SLog.i(TAG, "Downloading notebook ID: $notebookId")

        // Download and parse manifest
        val manifestJson = webdavClient.getFile("/Notable/notebooks/$notebookId/manifest.json").decodeToString()
        val notebook = notebookSerializer.deserializeManifest(manifestJson)

        SLog.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

        // Create notebook in local database FIRST (pages have foreign key to notebook)
        val existingNotebook = appRepository.bookRepository.getById(notebookId)
        if (existingNotebook != null) {
            // Preserve the remote timestamp when updating during sync
            appRepository.bookRepository.updatePreservingTimestamp(notebook)
        } else {
            appRepository.bookRepository.createEmpty(notebook)
        }

        // Download each page (now that notebook exists)
        for (pageId in notebook.pageIds) {
            try {
                downloadPage(pageId, notebookId, webdavClient)
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to download page $pageId: ${e.message}")
                // Continue with other pages
            }
        }

        SLog.i(TAG, "✓ Downloaded: ${notebook.title}")
    }

    /**
     * Download a single page with its strokes and images.
     */
    private suspend fun downloadPage(pageId: String, notebookId: String, webdavClient: WebDAVClient) {
        // Download page JSON
        val pageJson = webdavClient.getFile("/Notable/notebooks/$notebookId/pages/$pageId.json").decodeToString()
        val (page, strokes, images) = notebookSerializer.deserializePage(pageJson)

        // Download referenced images and update their URIs to local paths
        val updatedImages = images.map { image ->
            if (!image.uri.isNullOrEmpty()) {
                try {
                    val filename = extractFilename(image.uri)
                    val localFile = File(ensureImagesFolder(), filename)

                    if (!localFile.exists()) {
                        val remotePath = "/Notable/notebooks/$notebookId/images/$filename"
                        webdavClient.getFile(remotePath, localFile)
                        Log.i(TAG, "Downloaded image: $filename")
                    }

                    // Return image with updated local URI
                    image.copy(uri = localFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download image ${image.uri}: ${e.message}")
                    image
                }
            } else {
                image
            }
        }

        // Download custom backgrounds
        if (page.backgroundType != "native" && page.background != "blank") {
            try {
                val filename = page.background
                val localFile = File(ensureBackgroundsFolder(), filename)

                if (!localFile.exists()) {
                    val remotePath = "/Notable/notebooks/$notebookId/backgrounds/$filename"
                    webdavClient.getFile(remotePath, localFile)
                    Log.i(TAG, "Downloaded background: $filename")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download background ${page.background}: ${e.message}")
            }
        }

        // Save to local database
        val existingPage = appRepository.pageRepository.getById(page.id)
        if (existingPage != null) {
            // Page exists - delete old strokes/images and replace
            val existingStrokes = appRepository.pageRepository.getWithStrokeById(page.id).strokes
            val existingImages = appRepository.pageRepository.getWithImageById(page.id).images

            appRepository.strokeRepository.deleteAll(existingStrokes.map { it.id })
            appRepository.imageRepository.deleteAll(existingImages.map { it.id })

            appRepository.pageRepository.update(page)
        } else {
            // New page
            appRepository.pageRepository.create(page)
        }

        // Create strokes and images (using updated images with local URIs)
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

            // Delete existing notebooks on server (but keep /Notable structure)
            try {
                if (webdavClient.exists("/Notable/notebooks")) {
                    val existingNotebooks = webdavClient.listCollection("/Notable/notebooks")
                    SLog.i(TAG, "Deleting ${existingNotebooks.size} existing notebooks from server")
                    for (notebookDir in existingNotebooks) {
                        try {
                            webdavClient.delete("/Notable/notebooks/$notebookDir")
                        } catch (e: Exception) {
                            SLog.w(TAG, "Failed to delete $notebookDir: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                SLog.w(TAG, "Error cleaning server notebooks: ${e.message}")
            }

            // Ensure base structure exists
            if (!webdavClient.exists("/Notable")) {
                webdavClient.createCollection("/Notable")
            }
            if (!webdavClient.exists("/Notable/notebooks")) {
                webdavClient.createCollection("/Notable/notebooks")
            }

            // Upload all folders
            val folders = appRepository.folderRepository.getAll()
            if (folders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(folders)
                webdavClient.putFile("/Notable/folders.json", foldersJson.toByteArray(), "application/json")
                SLog.i(TAG, "Uploaded ${folders.size} folders")
            }

            // Upload all notebooks
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
            SLog.e(TAG, "Force upload failed: ${e.message}")
            e.printStackTrace()
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

            // Delete all local folders and notebooks
            val localFolders = appRepository.folderRepository.getAll()
            for (folder in localFolders) {
                appRepository.folderRepository.delete(folder.id)
            }

            val localNotebooks = appRepository.bookRepository.getAll()
            for (notebook in localNotebooks) {
                appRepository.bookRepository.delete(notebook.id)
            }
            SLog.i(TAG, "Deleted ${localFolders.size} folders and ${localNotebooks.size} local notebooks")

            // Download folders from server
            if (webdavClient.exists("/Notable/folders.json")) {
                val foldersJson = webdavClient.getFile("/Notable/folders.json").decodeToString()
                val folders = folderSerializer.deserializeFolders(foldersJson)
                for (folder in folders) {
                    appRepository.folderRepository.create(folder)
                }
                SLog.i(TAG, "Downloaded ${folders.size} folders from server")
            }

            // Download all notebooks from server
            if (webdavClient.exists("/Notable/notebooks")) {
                val notebookDirs = webdavClient.listCollection("/Notable/notebooks")
                SLog.i(TAG, "Found ${notebookDirs.size} notebook(s) on server")
                SLog.i(TAG, "Notebook directories: $notebookDirs")

                for (notebookDir in notebookDirs) {
                    try {
                        // Extract notebook ID from directory name
                        val notebookId = notebookDir.trimEnd('/')
                        SLog.i(TAG, "Downloading notebook: $notebookId")
                        downloadNotebook(notebookId, webdavClient)
                    } catch (e: Exception) {
                        SLog.e(TAG, "Failed to download $notebookDir: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } else {
                SLog.w(TAG, "/Notable/notebooks doesn't exist on server")
            }

            SLog.i(TAG, "✓ FORCE DOWNLOAD complete")
            SyncResult.Success
        } catch (e: Exception) {
            SLog.e(TAG, "Force download failed: ${e.message}")
            e.printStackTrace()
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
     * Initialize sync client by getting settings and credentials.
     * @return Pair of (AppSettings, WebDAVClient) or null if initialization fails
     */
    private suspend fun initializeSyncClient(): Pair<AppSettings, WebDAVClient>? {
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
     * Ensure required server directory structure exists.
     */
    private suspend fun ensureServerDirectories(webdavClient: WebDAVClient) {
        if (!webdavClient.exists("/Notable")) {
            webdavClient.createCollection("/Notable")
        }
        if (!webdavClient.exists("/Notable/notebooks")) {
            webdavClient.createCollection("/Notable/notebooks")
        }
    }

    /**
     * Sync all existing local notebooks.
     * @return Set of notebook IDs that existed before any new downloads
     */
    private suspend fun syncExistingNotebooks(): Set<String> {
        // IMPORTANT: Snapshot local notebook IDs BEFORE downloading to detect deletions correctly
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        SLog.i(TAG, "Found ${localNotebooks.size} local notebooks")

        for (notebook in localNotebooks) {
            try {
                syncNotebook(notebook.id)
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to sync ${notebook.title}: ${e.message}")
                // Continue with other notebooks even if one fails
            }
        }

        return preDownloadNotebookIds
    }

    /**
     * Discover and download new notebooks from server that don't exist locally.
     * @return Number of notebooks downloaded
     */
    private suspend fun downloadNewNotebooks(
        webdavClient: WebDAVClient,
        deletionsData: DeletionsData,
        settings: AppSettings,
        preDownloadNotebookIds: Set<String>
    ): Int {
        SLog.i(TAG, "Checking server for new notebooks...")

        if (!webdavClient.exists("/Notable/notebooks")) {
            return 0
        }

        val serverNotebookDirs = webdavClient.listCollection("/Notable/notebooks")
        SLog.i(TAG, "DEBUG: Server returned ${serverNotebookDirs.size} items: $serverNotebookDirs")
        SLog.i(TAG, "DEBUG: Local notebook IDs (before download): $preDownloadNotebookIds")

        val newNotebookIds = serverNotebookDirs
            .map { it.trimEnd('/') }
            .filter { it !in preDownloadNotebookIds }
            .filter { it !in deletionsData.deletedNotebookIds }  // Skip deleted notebooks
            .filter { it !in settings.syncSettings.syncedNotebookIds }  // Skip previously synced notebooks (they're local deletions, not new)

        SLog.i(TAG, "DEBUG: New notebook IDs after filtering: $newNotebookIds")

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
    private suspend fun updateSyncedNotebookIds(settings: AppSettings) {
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

        // Shared state across all SyncEngine instances
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        // Mutex to prevent concurrent syncs
        private val syncMutex = Mutex()

        /**
         * Update the sync state (internal use only).
         */
        internal fun updateState(state: SyncState) {
            _syncState.value = state
        }

        /**
         * Check if sync mutex is locked.
         */
        fun isSyncInProgress(): Boolean = syncMutex.isLocked
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
    SERVER_ERROR,
    CONFLICT_ERROR,
    SYNC_IN_PROGRESS,
    UNKNOWN_ERROR
}

/**
 * Represents the current state of a sync operation.
 */
sealed class SyncState {
    /**
     * No sync is currently running.
     */
    object Idle : SyncState()

    /**
     * Sync is currently in progress.
     * @param currentStep Which step of the sync process we're in
     * @param progress Overall progress from 0.0 to 1.0
     * @param details Human-readable description of current activity
     */
    data class Syncing(
        val currentStep: SyncStep,
        val progress: Float,
        val details: String
    ) : SyncState()

    /**
     * Sync completed successfully.
     * @param summary Statistics about what was synced
     */
    data class Success(
        val summary: SyncSummary
    ) : SyncState()

    /**
     * Sync failed with an error.
     * @param error The type of error that occurred
     * @param step Which step failed
     * @param canRetry Whether this error is potentially recoverable
     */
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
