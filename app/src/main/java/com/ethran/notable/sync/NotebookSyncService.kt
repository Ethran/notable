package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookSyncService @Inject constructor(
    private val appRepository: AppRepository,
    private val reporter: SyncProgressReporter
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

                    if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) {
                        log.i(
                            TAG,
                            "↻ Resurrecting '${localNotebook.title}' (modified after server deletion)"
                        )
                        continue
                    }

                    log.i(
                        TAG,
                        "Deleting locally (tombstone on server): ${localNotebook.title}"
                    )
                    try {
                        appRepository.bookRepository.delete(notebookId)
                    } catch (e: Exception) {
                        log.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                        errors.add(DomainError.DatabaseError("Failed to delete ${localNotebook.title}"))
                    }
                }
            }

            val cutoff = java.util.Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
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

    fun detectAndUploadLocalDeletions(
        client: WebDAVClient, settings: SyncSettings, preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Detecting local deletions...")
        val syncedNotebookIds = settings.syncedNotebookIds
        val deletedLocally = syncedNotebookIds - preDownloadNotebookIds
        val errors = ErrorAccumulator()

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
        settings: SyncSettings,
        preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Checking server for new notebooks...")
        val notebooksDirExists =
            client.exists(SyncPaths.notebooksDir()).onFailure { return AppResult.Error(it) }
        if (!notebooksDirExists) {
            return AppResult.Success(0)
        }
        return client.listCollection(SyncPaths.notebooksDir()).flatMap { serverNotebookDirs ->
            val newNotebookIds = serverNotebookDirs.map { it.trimEnd('/') }
                .filter { it !in preDownloadNotebookIds }
                .filter { it !in tombstonedIds }
                .filter { it !in settings.syncedNotebookIds }

            val errors = ErrorAccumulator()
            if (newNotebookIds.isNotEmpty()) {
                log.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
                val total = newNotebookIds.size
                newNotebookIds.forEachIndexed { i, notebookId ->
                    reporter.beginItem(index = i + 1, total = total, name = notebookId)
                    downloadNotebook(notebookId, client).onError { errors.add(it) }
                }
                reporter.endItem()
            } else {
                log.i(TAG, "No new notebooks on server")
            }

            errors.asResult(newNotebookIds.size)
        }
    }

    suspend fun uploadNotebook(
        notebook: Notebook,
        client: WebDAVClient,
        manifestIfMatch: String? = null
    ): AppResult<Unit, DomainError> {
        val notebookId = notebook.id
        log.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        return client.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/").flatMap {
            client.createCollection(SyncPaths.imagesDir(notebookId))
        }.flatMap {
            client.createCollection(SyncPaths.backgroundsDir(notebookId))
        }.flatMap {
            val manifestJson = NotebookSerializer.serializeManifest(notebook)
            client.putFile(
                SyncPaths.manifestFile(notebookId),
                manifestJson.toByteArray(),
                "application/json",
                ifMatch = manifestIfMatch
            )
        }.flatMap {
            val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
            val errors = ErrorAccumulator()
            for (page in pages) {
                uploadPage(page, notebookId, client).onError { errors.add(it) }
            }

            // Best-effort cleanup: if existence can't be determined, skip (a leftover tombstone is
            // harmless and will be re-checked next sync).
            val tombstonePath = SyncPaths.tombstone(notebookId)
            if (client.exists(tombstonePath).getOrElse { false }) {
                client.delete(tombstonePath).onSuccess {
                    log.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
                }
            }

            log.i(TAG, "Uploaded: ${notebook.title}")
            errors.asResult(Unit)
        }
    }

    private suspend fun uploadPage(
        page: Page,
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val pageWithData =
            appRepository.pageRepository.getWithDataById(page.id) ?: return AppResult.Error(
                DomainError.DatabaseError("Page data not found for page ID: ${page.id}")
            )
        val pageJson = NotebookSerializer.serializePage(
            page, pageWithData.strokes, pageWithData.images
        )
        return client.putFile(
            SyncPaths.pageFile(notebookId, page.id), pageJson.toByteArray(), "application/json"
        ).flatMap {
            val errors = ErrorAccumulator()
            for (image in pageWithData.images) {
                if (!image.uri.isNullOrEmpty()) {
                    val localFile = File(image.uri)
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
                        log.w(TAG, "Image file not found: ${image.uri}")
                    }
                }
            }

            if (page.backgroundType != "native" && page.background != "blank") {
                val bgFile = File(ensureBackgroundsFolder(), page.background)
                if (bgFile.exists()) {
                    val remotePath = SyncPaths.backgroundFile(notebookId, bgFile.name)
                    if (!client.exists(remotePath).getOrElse { false }) {
                        client.putFile(remotePath, bgFile, detectMimeType(bgFile)).onSuccess {
                            log.i(TAG, "Uploaded background: ${bgFile.name}")
                        }.onError { errors.add(it) }
                    }
                }
            }

            errors.asResult(Unit)
        }
    }

    suspend fun downloadNotebook(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Downloading notebook ID: $notebookId")

        // 1. Fetch manifest file (Early Return on error)
        val manifestBytes = client.getFile(SyncPaths.manifestFile(notebookId))
            .onFailure { return AppResult.Error(it) }

        // 2. Deserialize manifest (Early Return on corrupted JSON)
        val manifestJson = manifestBytes.decodeToString()
        val notebook = NotebookSerializer.deserializeManifest(manifestJson)
            .onFailure { return AppResult.Error(it) }

        log.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

        // 3. Save to database (protect against Room Exceptions)
        try {
            val existingNotebook = appRepository.bookRepository.getById(notebookId)
            if (existingNotebook != null) {
                appRepository.bookRepository.updatePreservingTimestamp(notebook)
            } else {
                appRepository.bookRepository.createEmpty(notebook)
            }
        } catch (e: Exception) {
            return AppResult.Error(DomainError.DatabaseError("Failed to update/create notebook locally: ${e.message}"))
        }

        // 4. Download pages and aggregate errors
        val errors = ErrorAccumulator()
        for (pageId in notebook.pageIds) {
            downloadPage(pageId, notebookId, client).onError { errors.add(it) }
        }

        log.i(TAG, "Downloaded: ${notebook.title}")
        return errors.asResult(Unit)
    }

    private suspend fun downloadPage(
        pageId: String,
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {

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
                    }
                        .onError { error ->
                            log.e(TAG, "Failed to download image $filename: ${error.userMessage}")
                            // Image download error doesn't interrupt the whole page sync,
                            // but is aggregated to notify the UI.
                            errors.add(error)
                        }
                }
                image.copy(uri = localFile.absolutePath)
            } else {
                image
            }
        }

        // 4. Download page background
        if (page.backgroundType != "native" && page.background != "blank") {
            val filename = page.background
            val localFile = File(ensureBackgroundsFolder(), filename)

            if (!localFile.exists()) {
                client.getFile(
                    SyncPaths.backgroundFile(notebookId, filename),
                    localFile
                ).onSuccess {
                    log.i(TAG, "Downloaded background: $filename")
                }.onError { error ->
                    log.e(TAG, "Failed to download background $filename: ${error.userMessage}")
                    errors.add(error)
                }
            }
        }

        // 5. Save to database (protect against Room Exceptions)
        try {
            val existingPage = appRepository.pageRepository.getById(page.id)
            if (existingPage != null) {
                val pageWithData =
                    appRepository.pageRepository.getWithDataById(page.id) ?: return AppResult.Error(
                        DomainError.DatabaseError("Failed to fetch existing page data for page ID: ${page.id}")
                    )
                appRepository.strokeRepository.deleteAll(pageWithData.strokes.map { it.id })
                appRepository.imageRepository.deleteAll(pageWithData.images.map { it.id })
                appRepository.pageRepository.update(page)
            } else {
                appRepository.pageRepository.create(page)
            }

            appRepository.strokeRepository.create(strokes)
            appRepository.imageRepository.create(updatedImages)

        } catch (e: Exception) {
            errors.add(DomainError.DatabaseError("Failed to save page $pageId: ${e.message}"))
        }

        // 6. Return aggregated result
        return errors.asResult(Unit)
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
