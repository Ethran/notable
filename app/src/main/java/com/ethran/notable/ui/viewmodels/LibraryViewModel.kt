package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.data.events.AppEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val folderId: String? = null,
    val isLatestVersion: Boolean = true,
    val isImporting: Boolean = false,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList()
)

// Private data class for clean Flow combining
private data class LibraryDatabaseState(
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    val appRepository: AppRepository,
    private val appEventBus: AppEventBus,
    val importEngine: ImportEngine,
    val exportEngine: ExportEngine,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    val pageDataManager: PageDataManager,
    private val snackDispatcher: SnackDispatcher,
    @param:ApplicationContext private val context: Context // Kept strictly for ImportEngine
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _newlyCreatedBookId = MutableStateFlow<String?>(null)
    val newlyCreatedBookId: StateFlow<String?> = _newlyCreatedBookId
    private val _isLatestVersion = MutableStateFlow(true)
    private val _breadcrumbFolders = MutableStateFlow<List<Folder>>(emptyList())

    // 1. Convert LiveData to Flow and switch automatically when folderId changes
    private val _foldersFlow =
        _folderId.flatMapLatest { id -> folderRepository.getAllInFolder(id).asFlow() }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }
    private val _singlePagesFlow =
        _folderId.flatMapLatest { id -> pageRepository.getSinglePagesInFolder(id).asFlow() }

    // 2. Group the 3 database flows semantically
    private val _dbDataFlow = combine(
        _foldersFlow, _booksFlow, _singlePagesFlow
    ) { folders, books, pages ->
        LibraryDatabaseState(folders, books, pages)
    }

    // 3. Expose the final UI State
    val uiState: StateFlow<LibraryUiState> = combine(
        _folderId, _isLatestVersion, _isImporting, _breadcrumbFolders, _dbDataFlow
    ) { folderId, isLatestVersion, isImporting, breadcrumbs, dbData ->
        LibraryUiState(
            folderId = folderId,
            isLatestVersion = isLatestVersion,
            isImporting = isImporting,
            breadcrumbFolders = breadcrumbs,
            folders = dbData.folders,
            books = dbData.books,
            singlePages = dbData.singlePages
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    init {
        // Run network/heavy checks in the background
        viewModelScope.launch(Dispatchers.IO) {
            _isLatestVersion.value = isLatestVersion(context, appEventBus, true)
        }
    }

    fun onPreviewRequested(pageId: String) {
        thumbnailBackfillQueue.enqueue(listOf(pageId))
    }

    fun loadFolder(folderId: String?) {
        pageDataManager.cancelLoadingPages()
        _folderId.value = folderId

        // Resolve breadcrumbs in background thread
        viewModelScope.launch(Dispatchers.IO) {
            _breadcrumbFolders.value = resolveBreadcrumbs(folderId)
        }
    }

    private suspend fun resolveBreadcrumbs(folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()

        val list = mutableListOf<Folder>()
        var currentId: String? = folderId

        while (currentId != null) {
            val folder = folderRepository.get(currentId)
            if (folder != null) {
                list.add(folder)
                currentId = folder.parentFolderId
            } else {
                currentId = null
            }
        }
        return list.reversed()
    }

    fun createNewFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = Folder(parentFolderId = _folderId.value)
            folderRepository.create(folder)
        }
    }

    fun deleteEmptyBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.delete(bookId)
        }
    }

    fun onCreateNewNotebook() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val notebook = Notebook(
                parentFolderId = _folderId.value,
                defaultBackground = settings.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key
            )
            bookRepository.create(notebook)
            _newlyCreatedBookId.value = notebook.id
        }
    }

    fun clearNewlyCreatedBookId() {
        _newlyCreatedBookId.value = null
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val snackText =
                if (copy) "Importing PDF background (copy)" else "Setting up observer for PDF"

            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(SnackConf(text = snackText, duration = 2000))

            try {
                // Ideally, ImportEngine should be injected via Hilt rather than instantiated here
                val result = importEngine.import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = !copy)
                )
                
                result.fold(
                    onSuccess = { importedPageIds ->
                        if (importedPageIds.isNotEmpty()) {
                            thumbnailBackfillQueue.enqueue(importedPageIds, PreviewSaveMode.STRICT_BW)
                        }
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "PDF Import Successful"))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Importing from xopp file...",
                    duration = 2000
                )
            )

            try {
                val result = importEngine.import(uri, ImportOptions(folderId = _folderId.value))
                result.fold(
                    onSuccess = { _ -> 
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "XOPP Import Successful", duration = 3000))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
