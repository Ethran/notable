package com.ethran.notable.io

import android.content.Context
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.getDbDir
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val log = ShipBook.getLogger("IndexExporter")

/**
 * Lightweight JSON index for PKM systems (Emacs, Obsidian, etc.) to navigate Notable data.
 *
 * The index contains only metadata (IDs, names, relationships) without stroke data,
 * keeping the file small regardless of notebook size.
 *
 * Index location: Documents/notabledb/notable-index.json
 *
 * Deep link reference:
 * - notable://page-{id} - Open page
 * - notable://book-{id} - Open book
 * - notable://new-folder?name=X&parent=Y - Create folder
 * - notable://new-book?name=X&folder=Y - Create book
 * - notable://new-page/{uuid}?name=X&folder=Y - Create quick page
 * - notable://book/{id}/new-page/{uuid}?name=X - Create page in book
 * - notable://export/page/{id}?format=pdf|png|jpg|xopp - Export page
 * - notable://export/book/{id}?format=pdf|png|xopp - Export book
 * - notable://sync-index - Refresh this index
 */
object IndexExporter {
    private const val INDEX_FILENAME = "notable-index.json"
    private const val DEBOUNCE_MS = 2000L

    private var exportJob: Job? = null
    private val json = Json { prettyPrint = true }

    /**
     * Schedule an index export with debouncing.
     * Multiple rapid calls will only trigger one export after the debounce period.
     */
    fun scheduleExport(context: Context) {
        exportJob?.cancel()
        exportJob = CoroutineScope(Dispatchers.IO).launch {
            delay(DEBOUNCE_MS)
            exportNow(context)
        }
    }

    /**
     * Export the index immediately, bypassing debounce.
     */
    fun exportNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val index = buildIndex(context)
                writeIndex(index)
                log.d("Index exported: ${index.folders.size} folders, ${index.notebooks.size} notebooks, ${index.pages.size} pages")
            } catch (e: Exception) {
                log.e("Failed to export index", e)
            }
        }
    }

    /**
     * Export the index synchronously (for use in deep link handlers).
     */
    fun exportSync(context: Context) {
        try {
            val index = buildIndex(context)
            writeIndex(index)
            log.d("Index exported (sync): ${index.folders.size} folders, ${index.notebooks.size} notebooks, ${index.pages.size} pages")
        } catch (e: Exception) {
            log.e("Failed to export index (sync)", e)
        }
    }

    private fun buildIndex(context: Context): NotableIndex {
        val repo = AppRepository(context)

        // Build folder map for path computation
        val allFolders = repo.folderRepository.getAll()
        val folderMap = allFolders.associateBy { it.id }

        val folders = allFolders.map { folder ->
            IndexFolder(
                id = folder.id,
                name = folder.title,
                parentId = folder.parentFolderId,
                path = buildFolderPath(folder, folderMap)
            )
        }

        val notebooks = repo.bookRepository.getAll().map { notebook ->
            IndexNotebook(
                id = notebook.id,
                name = notebook.title,
                folderId = notebook.parentFolderId,
                folderPath = notebook.parentFolderId?.let { buildFolderPathById(it, folderMap) },
                pageIds = notebook.pageIds,
                pageCount = notebook.pageIds.size
            )
        }

        val pages = repo.pageRepository.getAll().map { page ->
            // Find page index in notebook if it belongs to one
            val pageIndex = if (page.notebookId != null) {
                repo.bookRepository.getById(page.notebookId)?.pageIds?.indexOf(page.id)?.takeIf { it >= 0 }
            } else null

            IndexPage(
                id = page.id,
                name = page.name,
                notebookId = page.notebookId,
                folderId = page.parentFolderId,
                folderPath = page.parentFolderId?.let { buildFolderPathById(it, folderMap) },
                pageIndex = pageIndex
            )
        }

        return NotableIndex(
            version = 2,
            exportFormats = listOf("pdf", "png", "jpg", "xopp"),
            folders = folders,
            notebooks = notebooks,
            pages = pages
        )
    }

    private fun buildFolderPath(folder: Folder, folderMap: Map<String, Folder>): String {
        val pathParts = mutableListOf<String>()
        var current: Folder? = folder
        while (current != null) {
            pathParts.add(0, current.title)
            current = current.parentFolderId?.let { folderMap[it] }
        }
        return pathParts.joinToString("/")
    }

    private fun buildFolderPathById(folderId: String, folderMap: Map<String, Folder>): String? {
        val folder = folderMap[folderId] ?: return null
        return buildFolderPath(folder, folderMap)
    }

    private fun writeIndex(index: NotableIndex) {
        val dbDir = getDbDir()
        val indexFile = File(dbDir, INDEX_FILENAME)
        val jsonString = json.encodeToString(index)
        indexFile.writeText(jsonString)
    }

    /**
     * Get the path to the index file.
     */
    fun getIndexPath(): String {
        return File(getDbDir(), INDEX_FILENAME).absolutePath
    }
}

@Serializable
data class NotableIndex(
    val version: Int,
    val exportFormats: List<String>,
    val folders: List<IndexFolder>,
    val notebooks: List<IndexNotebook>,
    val pages: List<IndexPage>
)

@Serializable
data class IndexFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val path: String
)

@Serializable
data class IndexNotebook(
    val id: String,
    val name: String,
    val folderId: String?,
    val folderPath: String?,
    val pageIds: List<String>,
    val pageCount: Int
)

@Serializable
data class IndexPage(
    val id: String,
    val name: String?,
    val notebookId: String?,
    val folderId: String?,
    val folderPath: String?,
    val pageIndex: Int?
)
