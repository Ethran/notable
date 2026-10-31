package com.ethran.notable.ui.viewmodels

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.io.getLinkedFilesDir
import com.ethran.notable.sync.SyncRequest
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.ui.dialogs.FolderContents
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** The settings dialog of the notebook [bookId]: shows it and changes it. */
@HiltViewModel(assistedFactory = NotebookConfigViewModel.Factory::class)
class NotebookConfigViewModel @AssistedInject constructor(
    @Assisted private val bookId: String,
    private val appRepository: AppRepository,
    private val exportEngine: ExportEngine,
    private val syncScheduler: SyncScheduler,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(bookId: String): NotebookConfigViewModel
    }

    private val log = ShipBook.getLogger("NotebookConfigViewModel")
    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository

    /** Null until loaded, and once the notebook is deleted. */
    val book: StateFlow<Notebook?> = bookRepository.getByIdLive(bookId).asFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The folders the notebook is in, outermost first. */
    val breadcrumbFolders: StateFlow<List<Folder>> = book.map { it?.parentFolderId }
        .distinctUntilChanged()
        .map { getFolderList(appRepository, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the folder picker shows of [folderId]; null is the library's top level. */
    fun folderContents(folderId: String?): Flow<FolderContents> = combine(
        folderRepository.getAllInFolder(folderId).asFlow(),
        if (folderId == null) flowOf(null) else folderRepository.getParentLive(folderId).asFlow(),
    ) { children, parentId ->
        FolderContents(getFolderList(appRepository, folderId), parentId, children)
    }

    fun setTitle(title: String) {
        if (book.value?.title == title) return
        viewModelScope.launch { bookRepository.setTitle(bookId, title) }
    }

    /** A null [background] keeps the current one. */
    fun setDefaultBackground(type: String, background: String?) {
        viewModelScope.launch { bookRepository.setDefaultBackground(bookId, type, background) }
    }

    /** Links the notebook to a file in the default folder for linked files, or unlinks it. */
    fun setLinked(linked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = if (linked) getLinkedFilesDir().toUri().toString() else null
            bookRepository.setLinkedExternalUri(bookId, uri)
        }
    }

    fun moveTo(folderId: String?) {
        viewModelScope.launch { bookRepository.setParentFolder(bookId, folderId) }
    }

    fun delete() = launchWithSnack("Deleting notebook...") {
        bookRepository.delete(bookId)
        syncScheduler.triggerImmediateSync(SyncRequest.UploadDeletion(notebookId = bookId))
        log.i("Queued notebook deletion upload for $bookId")
        "Notebook deleted. Sync queued."
    }

    fun duplicate() = launchWithSnack("Duplicating notebook...") {
        if (appRepository.duplicateNotebook(bookId) != null) "Notebook duplicated."
        else "Duplicating failed: notebook not found."
    }

    fun export(format: ExportFormat) = launchWithSnack("Exporting the book to $format...") {
        exportEngine.export(target = ExportTarget.Book(bookId = bookId), format = format)
    }

    /**
     * Runs [block] on the view model's scope, which outlives the dialog, showing [during] while it
     * runs and then the message it returns.
     */
    private fun launchWithSnack(during: String, block: suspend () -> String) {
        viewModelScope.launch {
            val snackId = UUID.randomUUID().toString()
            snackDispatcher.showOrUpdateSnack(SnackConf(id = snackId, text = during, duration = null))
            val message = try {
                block()
            } catch (e: CancellationException) {
                snackDispatcher.removeSnack(snackId)
                throw e
            } catch (e: Exception) {
                log.e("$during failed: ${e.message}", e)
                "Error: ${e.message}"
            }
            snackDispatcher.showOrUpdateSnack(SnackConf(id = snackId, text = message, duration = 3000))
        }
    }
}
