package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.PagesDestination
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private val log = ShipBook.getLogger("Toolbar")


@Composable
fun PositionedToolbar(
    exportEngine: ExportEngine,
    navController: NavController,
    appRepository: AppRepository,
    editorState: EditorState,
    editorControlTower: EditorControlTower
) {
    val position = GlobalAppSettings.current.toolbarPosition
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var book by remember(editorState.bookId) { mutableStateOf<Notebook?>(null) }
    LaunchedEffect(editorState.bookId) {
        if (editorState.bookId != null) {
            val loadedBook = withContext(Dispatchers.IO) {
                appRepository.bookRepository.getById(editorState.bookId)
            }
            book = loadedBook
        }
    }

    val pageNumberInfo: String = remember(
        book?.id,
        editorState.currentPageId,
        book?.pageIds?.size
    ) {
        val currentPage = book?.let { (it.getPageIndex(editorState.currentPageId) + 1).toString() } ?: "?"
        val totalPages = book?.pageIds?.size?.toString() ?: "?"
        "$currentPage/$totalPages"
    }

    val toolbar = @Composable {
        Toolbar(
            state = editorState,
            controlTower = editorControlTower,
            pageNumberInfo = pageNumberInfo,
            onNavigateToLibrary = {
                scope.launch {
                    val page = withContext(Dispatchers.IO) {
                        appRepository.pageRepository.getById(editorState.currentPageId)
                    }
                    val parentFolder = withContext(Dispatchers.IO) {
                        page?.getParentFolder(appRepository.bookRepository)
                    }
                    navController.navigate(LibraryDestination.createRoute(parentFolder))
                }
            },
            onNavigateToBugReport = { navController.navigate(BugReportDestination.route) },
            onNavigateToPages = {
                if (editorState.bookId != null) {
                    navController.navigate(PagesDestination.createRoute(editorState.bookId))
                }
            },
            onNavigateToHome = { navController.navigate("library") },
            onToggleScribbleToErase = { enabled ->
                scope.launch(Dispatchers.IO) {
                    appRepository.kvProxy.setAppSettings(
                        GlobalAppSettings.current.copy(scribbleToEraseEnabled = enabled)
                    )
                }
            },
            onImagePicked = { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val copiedFile = copyImageToDatabase(context, uri)
                        CanvasEventBus.addImageByUri.value = copiedFile.toUri()
                    } catch (e: Exception) {
                        log.e("ImagePicker: copy failed: ${e.message}", e)
                    }
                }
            },
            onExport = { target, format ->
                exportEngine.export(target, format)
            }
        )
    }

    when (position) {
        AppSettings.Position.Top -> {
            toolbar()
        }

        AppSettings.Position.Bottom -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                toolbar()
            }
        }
    }
}