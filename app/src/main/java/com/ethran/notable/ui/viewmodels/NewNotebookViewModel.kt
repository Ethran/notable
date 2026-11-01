package com.ethran.notable.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.model.PageMode
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.ui.dialogs.FolderContents
import com.ethran.notable.ui.dialogs.folderContents
import com.ethran.notable.ui.views.NewNotebookDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_TITLE = "New notebook"

/** What the new notebook will be. */
data class NewNotebookState(
    val title: String,
    val folderId: String?,
    val backgroundType: String,
    val background: String,
    val pageMode: PageMode,
)

data class CreatedNotebook(val bookId: String, val pageId: String)

/** The new-notebook screen: holds the choices, and makes the notebook only on [create]. */
@HiltViewModel
class NewNotebookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appRepository: AppRepository,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {

    private val log = ShipBook.getLogger("NewNotebookViewModel")

    private val _state = MutableStateFlow(
        NewNotebookState(
            title = DEFAULT_TITLE,
            folderId = savedStateHandle[NewNotebookDestination.FOLDER_ID_ARG],
            backgroundType = BackgroundType.Native.key,
            background = GlobalAppSettings.current.defaultNativeTemplate,
            pageMode = GlobalAppSettings.current.defaultPageMode,
        )
    )
    val state: StateFlow<NewNotebookState> = _state.asStateFlow()

    /** The folders the notebook will be in, outermost first. */
    val breadcrumbFolders: StateFlow<List<Folder>> = _state.map { it.folderId }
        .distinctUntilChanged()
        .map { getFolderList(appRepository, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val createdChannel = Channel<CreatedNotebook>(Channel.BUFFERED)
    val created: Flow<CreatedNotebook> = createdChannel.receiveAsFlow()
    private var isCreating = false

    fun folderContents(folderId: String?): Flow<FolderContents> =
        folderContents(appRepository, folderId)

    fun setTitle(title: String) = _state.update { it.copy(title = title) }

    fun setFolder(folderId: String?) = _state.update { it.copy(folderId = folderId) }

    /** A null [background] keeps the current one. */
    fun setBackground(type: String, background: String?) = _state.update {
        it.copy(backgroundType = type, background = background ?: it.background)
    }

    fun setPageMode(mode: PageMode) = _state.update { it.copy(pageMode = mode) }

    fun create() {
        if (isCreating) return
        isCreating = true
        val state = _state.value
        val notebook = Notebook(
            title = state.title.trim().ifEmpty { DEFAULT_TITLE },
            parentFolderId = state.folderId,
            defaultBackground = state.background,
            defaultBackgroundType = state.backgroundType,
            pageMode = state.pageMode,
        )
        viewModelScope.launch {
            try {
                val pageId = appRepository.bookRepository.create(notebook)
                createdChannel.send(CreatedNotebook(notebook.id, pageId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("Creating notebook failed: ${e.message}", e)
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Creating notebook failed: ${e.message}", duration = 3000)
                )
                isCreating = false
            }
        }
    }
}
