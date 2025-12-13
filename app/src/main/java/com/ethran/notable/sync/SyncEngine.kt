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

            if (remoteExists) {
                // Fetch remote manifest and compare timestamps
                val remoteManifestJson = webdavClient.getFile(remotePath).decodeToString()
                val remoteUpdatedAt = notebookSerializer.getManifestUpdatedAt(remoteManifestJson)

                Log.i(TAG, "Remote updatedAt: $remoteUpdatedAt, Local updatedAt: ${localNotebook.updatedAt}")

                if (remoteUpdatedAt != null && remoteUpdatedAt.after(localNotebook.updatedAt)) {
                    // Remote is newer - download
                    Log.i(TAG, "Remote is newer, downloading notebook $notebookId (${localNotebook.title})")
                    downloadNotebook(notebookId, webdavClient)
                } else {
                    // Local is newer or equal - upload
                    Log.i(TAG, "Local is newer or equal, uploading notebook $notebookId (${localNotebook.title})")
                    uploadNotebook(localNotebook, webdavClient)
                }
            } else {
                // Remote doesn't exist - upload
                Log.i(TAG, "Notebook $notebookId (${localNotebook.title}) doesn't exist on server, uploading")
                uploadNotebook(localNotebook, webdavClient)
            }

            Log.i(TAG, "Notebook $notebookId synced successfully")
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
        Log.i(TAG, "Uploading notebook: ${notebook.title} ($notebookId)")

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

        Log.i(TAG, "Uploaded notebook ${notebook.title} with ${pages.size} pages")
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
        Log.i(TAG, "Downloading notebook: $notebookId")

        // Download and parse manifest
        val manifestJson = webdavClient.getFile("/Notable/notebooks/$notebookId/manifest.json").decodeToString()
        val notebook = notebookSerializer.deserializeManifest(manifestJson)

        // Download each page
        for (pageId in notebook.pageIds) {
            try {
                downloadPage(pageId, notebookId, webdavClient)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download page $pageId: ${e.message}")
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

        Log.i(TAG, "Downloaded notebook ${notebook.title} with ${notebook.pageIds.size} pages")
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
            Log.i(TAG, "FORCE UPLOAD: Replacing all server data with local data")

            val settings = kvProxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)

            val credentials = credentialManager.getCredentials()
                ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient = WebDAVClient(
                settings.syncSettings.serverUrl,
                credentials.first,
                credentials.second
            )

            // Delete existing Notable directory on server
            try {
                webdavClient.delete("/Notable")
            } catch (e: Exception) {
                // Directory might not exist, that's fine
            }

            // Recreate base structure
            webdavClient.createCollection("/Notable")
            webdavClient.createCollection("/Notable/notebooks")

            // Upload all folders
            val folders = appRepository.folderRepository.getAll()
            if (folders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(folders)
                webdavClient.putFile("/Notable/folders.json", foldersJson.toByteArray(), "application/json")
                Log.i(TAG, "Force uploaded ${folders.size} folders")
            }

            // Upload all notebooks
            val notebooks = appRepository.bookRepository.getAll()
            for (notebook in notebooks) {
                uploadNotebook(notebook, webdavClient)
            }

            Log.i(TAG, "FORCE UPLOAD complete: ${notebooks.size} notebooks uploaded")
            SyncResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Force upload failed: ${e.message}")
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
            Log.i(TAG, "FORCE DOWNLOAD: Replacing all local data with server data")

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
            Log.i(TAG, "Deleted ${localFolders.size} folders and ${localNotebooks.size} notebooks locally")

            // Download folders from server
            if (webdavClient.exists("/Notable/folders.json")) {
                val foldersJson = webdavClient.getFile("/Notable/folders.json").decodeToString()
                val folders = folderSerializer.deserializeFolders(foldersJson)
                for (folder in folders) {
                    appRepository.folderRepository.create(folder)
                }
                Log.i(TAG, "Downloaded ${folders.size} folders")
            }

            // Download all notebooks from server
            if (webdavClient.exists("/Notable/notebooks")) {
                val notebookDirs = webdavClient.listCollection("/Notable/notebooks")
                Log.i(TAG, "Found ${notebookDirs.size} notebook directories on server: $notebookDirs")

                for (notebookDir in notebookDirs) {
                    try {
                        // Extract notebook ID from directory name
                        val notebookId = notebookDir.trimEnd('/')
                        Log.i(TAG, "Downloading notebook: $notebookId")
                        downloadNotebook(notebookId, webdavClient)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download notebook $notebookDir: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } else {
                Log.w(TAG, "/Notable/notebooks directory doesn't exist on server")
            }

            Log.i(TAG, "FORCE DOWNLOAD complete")
            SyncResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Force download failed: ${e.message}")
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
