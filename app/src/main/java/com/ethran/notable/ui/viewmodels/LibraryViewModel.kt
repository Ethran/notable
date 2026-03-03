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
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    // Data from DB
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList() // Assuming it's a list of Page objects
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    appRepository: AppRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _isLatestVersion = MutableStateFlow(true)
    private val _breadcrumbFolders = MutableStateFlow<List<Folder>>(emptyList())

    // Convert LiveData to Flow and switch automatically when folderId changes
    private val _foldersFlow =
        _folderId.flatMapLatest { id -> folderRepository.getAllInFolder(id).asFlow() }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }
    private val _singlePagesFlow =
        _folderId.flatMapLatest { id -> pageRepository.getSinglePagesInFolder(id).asFlow() }

    // 1. Group the 3 database flows into a single Flow<Triple>
    private val _dbDataFlow = combine(
        _foldersFlow, _booksFlow, _singlePagesFlow
    ) { folders, books, pages ->
        Triple(folders, books, pages)
    }

    // 2. Combine the remaining flows with the grouped DB flow (Total: 5 parameters)
    val uiState: StateFlow<LibraryUiState> = combine(
        _folderId, _isLatestVersion, _isImporting, _breadcrumbFolders, _dbDataFlow
    ) { folderId, isLatestVersion, isImporting, breadcrumbs, dbData ->
        LibraryUiState(
            folderId = folderId,
            isLatestVersion = isLatestVersion,
            isImporting = isImporting,
            breadcrumbFolders = breadcrumbs,
            folders = dbData.first,
            books = dbData.second,
            singlePages = dbData.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    init {
        // Run network/heavy checks in the background
        viewModelScope.launch(Dispatchers.IO) {
            _isLatestVersion.value = isLatestVersion(context, true)
        }
    }

    fun loadFolder(folderId: String?) {
        PageDataManager.cancelLoadingPages()
        _folderId.value = folderId

        // Resolve breadcrumbs in background thread
        viewModelScope.launch(Dispatchers.IO) {
            val breadcrumbs =
                if (folderId != null) getFolderList(context, folderId).reversed() else emptyList()
            _breadcrumbFolders.value = breadcrumbs
        }
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
            // Read settings from storage, not CompositionLocal!
            val settings = GlobalAppSettings.current
            bookRepository.create(
                Notebook(
                    parentFolderId = _folderId.value,
                    defaultBackground = settings.defaultNativeTemplate,
                    defaultBackgroundType = BackgroundType.Native.key
                )
            )
        }
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val snackText =
                if (copy) "Importing PDF background (copy)" else "Setting up observer for PDF"

            _isImporting.value = true
            SnackState.globalSnackFlow.tryEmit(SnackConf(text = snackText, duration = 2000))

            try {
                ImportEngine(context).import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = !copy)
                )
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "PDF Import Successful"))
            } catch (e: Exception) {
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    text = "Importing from xopp file...", duration = 2000
                )
            )

            try {
                ImportEngine(context).import(
                    uri, ImportOptions(folderId = _folderId.value)
                )
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "XOPP Import Successful"))
            } catch (e: Exception) {
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
