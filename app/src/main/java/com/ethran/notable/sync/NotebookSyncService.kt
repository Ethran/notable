package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onSuccess
import com.ethran.notable.utils.plus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookSyncService @Inject constructor(
    private val appRepository: AppRepository,
    private val reporter: SyncProgressReporter
) {
    private val notebookSerializer = NotebookSerializer()
    private val sLog = SyncLogger

    suspend fun applyRemoteDeletions(
        webdavClient: WebDAVClient,
        maxAgeDays: Long
    ): AppResult<Set<String>, DomainError> {
        sLog.i(TAG, "Applying remote deletions...")
        val tombstonesPath = SyncPaths.tombstonesDir()

        if (!webdavClient.exists(tombstonesPath)) return AppResult.Success(emptySet())

        return webdavClient.listCollectionWithMetadata(tombstonesPath).flatMap { tombstones ->
            val tombstonedIds = tombstones.map { it.name }.toSet()
            var persistentError: DomainError? = null

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

                    sLog.i(
                        TAG,
                        "Deleting locally (tombstone on server): ${localNotebook.title}"
                    )
                    try {
                        appRepository.bookRepository.delete(notebookId)
                    } catch (e: Exception) {
                        sLog.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                        val error = DomainError.DatabaseError("Failed to delete ${localNotebook.title}")
                        persistentError = persistentError?.plus(error) ?: error
                    }
                }
            }

            val cutoff = java.util.Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
            val stale = tombstones.filter { it.lastModified != null && it.lastModified.before(cutoff) }
            if (stale.isNotEmpty()) {
                sLog.i(TAG, "Pruning ${stale.size} stale tombstone(s) older than $maxAgeDays days")
                for (entry in stale) {
                    webdavClient.delete(SyncPaths.tombstone(entry.name)).onError {
                        sLog.w(TAG, "Failed to prune tombstone ${entry.name}: ${it.userMessage}")
                    }
                }
            }

            if (persistentError != null) AppResult.Error(persistentError)
            else AppResult.Success(tombstonedIds)
        }
    }

    suspend fun detectAndUploadLocalDeletions(
        webdavClient: WebDAVClient, settings: SyncSettings, preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        sLog.i(TAG, "Detecting local deletions...")
        val syncedNotebookIds = settings.syncedNotebookIds
        val deletedLocally = syncedNotebookIds - preDownloadNotebookIds
        var persistentError: DomainError? = null

        if (deletedLocally.isNotEmpty()) {
            sLog.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")
            for (notebookId in deletedLocally) {
                val notebookPath = SyncPaths.notebookDir(notebookId)
                if (webdavClient.exists(notebookPath)) {
                    sLog.i(TAG, "Deleting from server: $notebookId")
                    webdavClient.delete(notebookPath).onError { error ->
                        persistentError = persistentError?.plus(error) ?: error
                    }
                }
                webdavClient.putFile(
                    SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream"
                ).onSuccess {
                    sLog.i(TAG, "Tombstone uploaded for: $notebookId")
                }.onError { error ->
                    sLog.e(TAG, "Failed to upload tombstone for $notebookId: ${error.userMessage}")
                    persistentError = persistentError?.plus(error) ?: error
                }
            }
        } else {
            sLog.i(TAG, "No local deletions detected")
        }

        return if (persistentError != null) AppResult.Error(persistentError)
        else AppResult.Success(deletedLocally.size)
    }

    suspend fun downloadNewNotebooks(
        webdavClient: WebDAVClient,
        tombstonedIds: Set<String>,
        settings: SyncSettings,
        preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        sLog.i(TAG, "Checking server for new notebooks...")
        if (!webdavClient.exists(SyncPaths.notebooksDir())) {
            return AppResult.Success(0)
        }
        return webdavClient.listCollection(SyncPaths.notebooksDir()).flatMap { serverNotebookDirs ->
            val newNotebookIds = serverNotebookDirs.map { it.trimEnd('/') }
                .filter { it !in preDownloadNotebookIds }
                .filter { it !in tombstonedIds }
                .filter { it !in settings.syncedNotebookIds }

            var persistentError: DomainError? = null
            if (newNotebookIds.isNotEmpty()) {
                sLog.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
                val total = newNotebookIds.size
                newNotebookIds.forEachIndexed { i, notebookId ->
                    reporter.beginItem(index = i + 1, total = total, name = notebookId)
                    downloadNotebook(notebookId, webdavClient).onError { error ->
                        persistentError = persistentError?.plus(error) ?: error
                    }
                }
                reporter.endItem()
            } else {
                sLog.i(TAG, "No new notebooks on server")
            }

            if (persistentError != null) AppResult.Error(persistentError)
            else AppResult.Success(newNotebookIds.size)
        }
    }

    suspend fun uploadNotebook(
        notebook: Notebook,
        webdavClient: WebDAVClient,
        manifestIfMatch: String? = null
    ): AppResult<Unit, DomainError> {
        val notebookId = notebook.id
        sLog.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        return webdavClient.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/").flatMap {
            webdavClient.createCollection(SyncPaths.imagesDir(notebookId))
        }.flatMap {
            webdavClient.createCollection(SyncPaths.backgroundsDir(notebookId))
        }.flatMap {
            val manifestJson = notebookSerializer.serializeManifest(notebook)
            webdavClient.putFile(
                SyncPaths.manifestFile(notebookId),
                manifestJson.toByteArray(),
                "application/json",
                ifMatch = manifestIfMatch
            )
        }.flatMap {
            val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
            var persistentError: DomainError? = null
            for (page in pages) {
                uploadPage(page, notebookId, webdavClient).onError { error ->
                    persistentError = persistentError?.plus(error) ?: error
                }
            }

            val tombstonePath = SyncPaths.tombstone(notebookId)
            if (webdavClient.exists(tombstonePath)) {
                webdavClient.delete(tombstonePath).onSuccess {
                    sLog.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
                }
            }

            sLog.i(TAG, "Uploaded: ${notebook.title}")
            if (persistentError != null) AppResult.Error(persistentError)
            else AppResult.Success(Unit)
        }
    }

    private suspend fun uploadPage(
        page: Page,
        notebookId: String,
        webdavClient: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val pageWithData = appRepository.pageRepository.getWithDataById(page.id)
        val pageJson = notebookSerializer.serializePage(
            page, pageWithData.strokes, pageWithData.images
        )
        return webdavClient.putFile(
            SyncPaths.pageFile(notebookId, page.id), pageJson.toByteArray(), "application/json"
        ).flatMap {
            var persistentError: DomainError? = null
            for (image in pageWithData.images) {
                if (!image.uri.isNullOrEmpty()) {
                    val localFile = File(image.uri)
                    if (localFile.exists()) {
                        val remotePath = SyncPaths.imageFile(notebookId, localFile.name)
                        if (!webdavClient.exists(remotePath)) {
                            webdavClient.putFile(remotePath, localFile, detectMimeType(localFile)).onSuccess {
                                sLog.i(TAG, "Uploaded image: ${localFile.name}")
                            }.onError { error ->
                                persistentError = persistentError?.plus(error) ?: error
                            }
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
                        webdavClient.putFile(remotePath, bgFile, detectMimeType(bgFile)).onSuccess {
                            sLog.i(TAG, "Uploaded background: ${bgFile.name}")
                        }.onError { error ->
                            persistentError = persistentError?.plus(error) ?: error
                        }
                    }
                }
            }

            if (persistentError != null) AppResult.Error(persistentError!!)
            else AppResult.Success(Unit)
        }
    }

    suspend fun downloadNotebook(
        notebookId: String,
        webdavClient: WebDAVClient
    ): AppResult<Unit, DomainError> {
        sLog.i(TAG, "Downloading notebook ID: $notebookId")
        return webdavClient.getFile(SyncPaths.manifestFile(notebookId)).flatMap { manifestBytes ->
            val manifestJson = manifestBytes.decodeToString()
            val notebook = notebookSerializer.deserializeManifest(manifestJson)
            sLog.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

            try {
                val existingNotebook = appRepository.bookRepository.getById(notebookId)
                if (existingNotebook != null) {
                    appRepository.bookRepository.updatePreservingTimestamp(notebook)
                } else {
                    appRepository.bookRepository.createEmpty(notebook)
                }
            } catch (e: Exception) {
                return@flatMap AppResult.Error(DomainError.DatabaseError("Failed to update/create notebook locally: ${e.message}"))
            }

            var persistentError: DomainError? = null
            for (pageId in notebook.pageIds) {
                downloadPage(pageId, notebookId, webdavClient).onError { error ->
                    persistentError = persistentError?.plus(error) ?: error
                }
            }

            sLog.i(TAG, "Downloaded: ${notebook.title}")
            if (persistentError != null) AppResult.Error(persistentError)
            else AppResult.Success(Unit)
        }
    }

    private suspend fun downloadPage(
        pageId: String, notebookId: String, webdavClient: WebDAVClient
    ): AppResult<Unit, DomainError> {
        return webdavClient.getFile(SyncPaths.pageFile(notebookId, pageId)).flatMap { pageBytes ->
            val pageJson = pageBytes.decodeToString()
            val (page, strokes, images) = notebookSerializer.deserializePage(pageJson)

            var persistentError: DomainError? = null

            val updatedImages = images.map { image ->
                if (!image.uri.isNullOrEmpty()) {
                    val filename = extractFilename(image.uri)
                    val localFile = File(ensureImagesFolder(), filename)
                    if (!localFile.exists()) {
                        webdavClient.getFile(
                            SyncPaths.imageFile(notebookId, filename),
                            localFile
                        ).onSuccess {
                            sLog.i(TAG, "Downloaded image: $filename")
                        }.onError { error ->
                            sLog.e(TAG, "Failed to download image $filename: ${error.userMessage}")
                            persistentError = persistentError?.plus(error) ?: error
                        }
                    }
                    image.copy(uri = localFile.absolutePath)
                } else {
                    image
                }
            }

            if (page.backgroundType != "native" && page.background != "blank") {
                val filename = page.background
                val localFile = File(ensureBackgroundsFolder(), filename)
                if (!localFile.exists()) {
                    webdavClient.getFile(
                        SyncPaths.backgroundFile(notebookId, filename),
                        localFile
                    ).onSuccess {
                        sLog.i(TAG, "Downloaded background: $filename")
                    }.onError { error ->
                        sLog.e(TAG, "Failed to download background $filename: ${error.userMessage}")
                        persistentError = persistentError?.plus(error) ?: error
                    }
                }
            }

            try {
                val existingPage = appRepository.pageRepository.getById(page.id)
                if (existingPage != null) {
                    val pageWithData = appRepository.pageRepository.getWithDataById(page.id)
                    appRepository.strokeRepository.deleteAll(pageWithData.strokes.map { it.id })
                    appRepository.imageRepository.deleteAll(pageWithData.images.map { it.id })
                    appRepository.pageRepository.update(page)
                } else {
                    appRepository.pageRepository.create(page)
                }
                appRepository.strokeRepository.create(strokes)
                appRepository.imageRepository.create(updatedImages)
            } catch (e: Exception) {
                val dbError = DomainError.DatabaseError("Failed to save page $pageId: ${e.message}")
                persistentError = persistentError?.plus(dbError) ?: dbError
            }

            if (persistentError != null) AppResult.Error(persistentError)
            else AppResult.Success(Unit)
        }
    }

    private fun extractFilename(uri: String): String = uri.substringAfterLast('/')

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
