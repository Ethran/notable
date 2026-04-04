package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.sync.serializers.NotebookSerializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookSyncService @Inject constructor(
    private val appRepository: AppRepository
) {
    private val notebookSerializer = NotebookSerializer()
    private val TAG = "NotebookSyncService"
    private val sLog = SyncLogger
    suspend fun applyRemoteDeletions(webdavClient: WebDAVClient, maxAgeDays: Long): Set<String> {
        sLog.i(TAG, "Applying remote deletions...")
        val tombstonesPath = SyncPaths.tombstonesDir()
        if (!webdavClient.exists(tombstonesPath)) return emptySet()
        val tombstones = webdavClient.listCollectionWithMetadata(tombstonesPath)
        val tombstonedIds = tombstones.map { it.name }.toSet()
        if (tombstones.isNotEmpty()) {
            sLog.i(TAG, "Server has ${tombstones.size} tombstone(s)")
            for (tombstone in tombstones) {
                val notebookId = tombstone.name
                val deletedAt = tombstone.lastModified
                val localNotebook = appRepository.bookRepository.getById(notebookId) ?: continue
                if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) {
                    sLog.i(
                        TAG,
                        "↻ Resurrecting '${localNotebook.title}' (modified after server deletion)"
                    )
                    continue
                }
                try {
                    sLog.i(TAG, "Deleting locally (tombstone on server): ${localNotebook.title}")
                    appRepository.bookRepository.delete(notebookId)
                } catch (e: Exception) {
                    sLog.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                }
            }
        }
        val cutoff = java.util.Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
        val stale = tombstones.filter { it.lastModified != null && it.lastModified.before(cutoff) }
        if (stale.isNotEmpty()) {
            sLog.i(TAG, "Pruning ${stale.size} stale tombstone(s) older than $maxAgeDays days")
            for (entry in stale) {
                try {
                    webdavClient.delete(SyncPaths.tombstone(entry.name))
                } catch (e: Exception) {
                    sLog.w(TAG, "Failed to prune tombstone ${entry.name}: ${e.message}")
                }
            }
        }
        return tombstonedIds
    }

    fun detectAndUploadLocalDeletions(
        webdavClient: WebDAVClient, settings: SyncSettings, preDownloadNotebookIds: Set<String>
    ): Int {
        sLog.i(TAG, "Detecting local deletions...")
        val syncedNotebookIds = settings.syncedNotebookIds
        val deletedLocally = syncedNotebookIds - preDownloadNotebookIds
        if (deletedLocally.isNotEmpty()) {
            sLog.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")
            for (notebookId in deletedLocally) {
                try {
                    val notebookPath = SyncPaths.notebookDir(notebookId)
                    if (webdavClient.exists(notebookPath)) {
                        sLog.i(TAG, "Deleting from server: $notebookId")
                        webdavClient.delete(notebookPath)
                    }
                    webdavClient.putFile(
                        SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream"
                    )
                    sLog.i(TAG, "Tombstone uploaded for: $notebookId")
                } catch (e: Exception) {
                    sLog.e(TAG, "Failed to process local deletion $notebookId: ${e.message}")
                }
            }
        } else {
            sLog.i(TAG, "No local deletions detected")
        }
        return deletedLocally.size
    }

    suspend fun downloadNewNotebooks(
        webdavClient: WebDAVClient,
        tombstonedIds: Set<String>,
        settings: SyncSettings,
        preDownloadNotebookIds: Set<String>
    ): Int {
        sLog.i(TAG, "Checking server for new notebooks...")
        if (!webdavClient.exists(SyncPaths.notebooksDir())) {
            return 0
        }
        val serverNotebookDirs = webdavClient.listCollection(SyncPaths.notebooksDir())
        val newNotebookIds =
            serverNotebookDirs.map { it.trimEnd('/') }.filter { it !in preDownloadNotebookIds }
                .filter { it !in tombstonedIds }
                .filter { it !in settings.syncedNotebookIds }
        if (newNotebookIds.isNotEmpty()) {
            sLog.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
            for (notebookId in newNotebookIds) {
                try {
                    sLog.i(TAG, "Downloading new notebook from server: $notebookId")
                    downloadNotebook(notebookId, webdavClient)
                } catch (e: Exception) {
                    sLog.e(TAG, "Failed to download $notebookId: ${e.message}")
                }
            }
        } else {
            sLog.i(TAG, "No new notebooks on server")
        }
        return newNotebookIds.size
    }

    suspend fun uploadNotebook(
        notebook: Notebook,
        webdavClient: WebDAVClient,
        manifestIfMatch: String? = null
    ) {
        val notebookId = notebook.id
        sLog.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")
        webdavClient.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/")
        webdavClient.createCollection(SyncPaths.imagesDir(notebookId))
        webdavClient.createCollection(SyncPaths.backgroundsDir(notebookId))
        val manifestJson = notebookSerializer.serializeManifest(notebook)
        webdavClient.putFile(
            SyncPaths.manifestFile(notebookId),
            manifestJson.toByteArray(),
            "application/json",
            ifMatch = manifestIfMatch
        )
        val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
        for (page in pages) {
            uploadPage(page, notebookId, webdavClient)
        }
        val tombstonePath = SyncPaths.tombstone(notebookId)
        if (webdavClient.exists(tombstonePath)) {
            webdavClient.delete(tombstonePath)
            sLog.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
        }
        sLog.i(TAG, "Uploaded: ${notebook.title}")
    }

    private suspend fun uploadPage(page: Page, notebookId: String, webdavClient: WebDAVClient) {
        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(page.id)
        val pageWithImages = appRepository.pageRepository.getWithImageById(page.id)
        val pageJson = notebookSerializer.serializePage(
            page, pageWithStrokes.strokes, pageWithImages.images
        )
        webdavClient.putFile(
            SyncPaths.pageFile(notebookId, page.id), pageJson.toByteArray(), "application/json"
        )
        for (image in pageWithImages.images) {
            if (!image.uri.isNullOrEmpty()) {
                val localFile = File(image.uri)
                if (localFile.exists()) {
                    val remotePath = SyncPaths.imageFile(notebookId, localFile.name)
                    if (!webdavClient.exists(remotePath)) {
                        webdavClient.putFile(remotePath, localFile, detectMimeType(localFile))
                        sLog.i(TAG, "Uploaded image: ${localFile.name}")
                    }
                } else {
                    sLog.w(TAG, "Image file not found: ${image.uri}")
                }
            }
        }
        if (page.backgroundType != "native" && page.background != "blank") {
            val bgFile = File(ensureBackgroundsFolder(), page.background)
            if (bgFile.exists()) {
                val remotePath = SyncPaths.backgroundFile(notebookId, bgFile.name)
                if (!webdavClient.exists(remotePath)) {
                    webdavClient.putFile(remotePath, bgFile, detectMimeType(bgFile))
                    sLog.i(TAG, "Uploaded background: ${bgFile.name}")
                }
            }
        }
    }

    suspend fun downloadNotebook(notebookId: String, webdavClient: WebDAVClient) {
        sLog.i(TAG, "Downloading notebook ID: $notebookId")
        val manifestJson = webdavClient.getFile(SyncPaths.manifestFile(notebookId)).decodeToString()
        val notebook = notebookSerializer.deserializeManifest(manifestJson)
        sLog.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")
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
                sLog.e(TAG, "Failed to download page $pageId: ${e.message}")
            }
        }
        sLog.i(TAG, "Downloaded: ${notebook.title}")
    }

    private suspend fun downloadPage(
        pageId: String, notebookId: String, webdavClient: WebDAVClient
    ) {
        val pageJson = webdavClient.getFile(SyncPaths.pageFile(notebookId, pageId)).decodeToString()
        val (page, strokes, images) = notebookSerializer.deserializePage(pageJson)
        val updatedImages = images.map { image ->
            if (!image.uri.isNullOrEmpty()) {
                try {
                    val filename = extractFilename(image.uri)
                    val localFile = File(ensureImagesFolder(), filename)
                    if (!localFile.exists()) {
                        webdavClient.getFile(SyncPaths.imageFile(notebookId, filename), localFile)
                        sLog.i(TAG, "Downloaded image: $filename")
                    }
                    image.copy(uri = localFile.absolutePath)
                } catch (e: Exception) {
                    sLog.e(
                        TAG,
                        "Failed to download image ${image.uri}: ${e.message}\n${e.stackTraceToString()}"
                    )
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
                    sLog.i(TAG, "Downloaded background: $filename")
                }
            } catch (e: Exception) {
                sLog.e(
                    TAG,
                    "Failed to download background ${page.background}: ${e.message}\n${e.stackTraceToString()}"
                )
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

    private fun extractFilename(uri: String): String {
        return uri.substringAfterLast('/')
    }

    private fun detectMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
