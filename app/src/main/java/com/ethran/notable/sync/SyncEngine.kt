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
        return@withContext try {
            SLog.i(TAG, "Starting full sync...")

            // Get sync settings and credentials
            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            if (!settings.syncSettings.syncEnabled) {
                SLog.i(TAG, "Sync disabled in settings")
                return@withContext SyncResult.Success
            }

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            // Ensure base directory exists
            if (!webdavClient.exists("/Notable")) {
                webdavClient.createCollection("/Notable")
            }
            if (!webdavClient.exists("/Notable/notebooks")) {
                webdavClient.createCollection("/Notable/notebooks")
            }

            // 1. Sync folders first (they're referenced by notebooks)
            syncFolders(webdavClient)

            // 2. Sync all notebooks
            val notebooks = appRepository.bookRepository.getAll()
            SLog.i(TAG, "Found ${notebooks.size} local notebooks to sync")

            for (notebook in notebooks) {
                try {
                    syncNotebook(notebook.id)
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to sync notebook ${notebook.title}: ${e.message}")
                    // Continue with other notebooks even if one fails
                }
            }

            // 3. Sync Quick Pages (pages with notebookId = null)
            // TODO: Implement Quick Pages sync

            SLog.i(TAG, "✓ Full sync completed successfully")
            SyncResult.Success
        } catch (e: IOException) {
            SLog.e(TAG, "Network error during sync: ${e.message}")
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            SLog.e(TAG, "Unexpected error during sync: ${e.message}")
            e.printStackTrace()
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
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

                // Use 1-second tolerance to ignore millisecond precision differences
                when {
                    remoteUpdatedAt == null -> {
                        SLog.i(TAG, "↑ No remote timestamp, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    diffMs < -1000 -> {
                        // Remote is newer by > 1 second - download
                        SLog.i(TAG, "↓ Remote newer, downloading ${localNotebook.title}")
                        downloadNotebook(notebookId, webdavClient)
                    }
                    diffMs > 1000 -> {
                        // Local is newer by > 1 second - upload
                        SLog.i(TAG, "↑ Local newer, uploading ${localNotebook.title}")
                        uploadNotebook(localNotebook, webdavClient)
                    }
                    else -> {
                        // Within 1 second - no significant change
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

        // Download each page
        for (pageId in notebook.pageIds) {
            try {
                downloadPage(pageId, notebookId, webdavClient)
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to download page $pageId: ${e.message}")
                // Continue with other pages
            }
        }

        // Update or create notebook in local database
        val existingNotebook = appRepository.bookRepository.getById(notebookId)
        if (existingNotebook != null) {
            appRepository.bookRepository.update(notebook)
        } else {
            appRepository.bookRepository.createEmpty(notebook)
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

    companion object {
        private const val TAG = "SyncEngine"
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
    UNKNOWN_ERROR
}
