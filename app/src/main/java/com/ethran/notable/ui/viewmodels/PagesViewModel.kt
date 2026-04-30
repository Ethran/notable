package com.ethran.notable.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.components.getFolderList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


data class PagesUiState(
    val bookId: String = "",
    val pageIds: List<String> = emptyList(),
    val openPageId: String? = null,
    val folderList: List<Folder> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class PagesViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PagesUiState())
    val uiState: StateFlow<PagesUiState> = _uiState.asStateFlow()

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            appRepository.bookRepository.getByIdLive(bookId).asFlow().collect { book ->
                if (book != null) {
                    val folderList = getFolderList(appRepository, book.parentFolderId)
                    _uiState.update { it.copy(
                        bookId = bookId,
                        pageIds = book.pageIds,
                        openPageId = book.openPageId,
                        folderList = folderList,
                        isLoading = false
                    ) }
                }
            }
        }
    }

    fun reorderPage(bookId: String, pageId: String, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.bookRepository.changePageIndex(bookId, pageId, toIndex)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.ethran.notable.data.deletePage(appRepository, pageId, context.filesDir)
        }
    }

    fun duplicatePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.duplicatePage(pageId)
        }
    }

    fun newPageInBook(bookId: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.newPageInBook(bookId, index)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.pageIds
        if (pageIds.isEmpty()) return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}