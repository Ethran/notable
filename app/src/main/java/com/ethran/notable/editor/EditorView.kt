package com.ethran.notable.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.toolbar.Toolbar
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.exportToLinkedFile
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.views.LibraryDestination
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


object EditorDestination : NavigationDestination {
    override val route = "editor"

    const val PAGE_ID_ARG = "pageId"
    const val BOOK_ID_ARG = "bookId"

    // Unified route: editor/{pageId}?bookId={bookId}
    val routeWithArgs = "$route/{$PAGE_ID_ARG}?$BOOK_ID_ARG={$BOOK_ID_ARG}"

    /**
     * Helper to create the path. If bookId is null, it just won't be appended.
     */
    fun createRoute(pageId: String, bookId: String? = null): String {
        return "$route/$pageId" + if (bookId != null) "?$BOOK_ID_ARG=$bookId" else ""
    }
}


@Composable
fun EditorView(
    editorSettingCacheManager: EditorSettingCacheManager,
    exportEngine: ExportEngine,
    navController: NavController,
    appRepository: AppRepository,
    bookId: String?,
    pageId: String,
    onPageChange: (String) -> Unit
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()

    var pageExists by remember(pageId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pageId) {
        val exists = withContext(Dispatchers.IO) {
            appRepository.pageRepository.getById(pageId) != null
        }
        pageExists = exists

        if (!exists) {
            // TODO: check if it is correct, and remove exeption throwing
            throw Exception("Page does not exist")
            if (bookId != null) {
                // clean the book
                Log.i(TAG, "Could not find page, Cleaning book")
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Could not find page, cleaning book",
                        duration = 4000
                    )
                )
                scope.launch(Dispatchers.IO) {
                    appRepository.bookRepository.removePage(bookId, pageId)
                }
            }
            navController.navigate(LibraryDestination.route)
        }
    }


    if (pageExists == null) return

    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()


        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                appRepository = appRepository,
                currentPageId = pageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
            )
        }

        val editorState =
            remember {
                EditorState(
                    appRepository = appRepository,
                    bookId = bookId,
                    pageId = pageId,
                    pageView = page,
                    persistedEditorSettings = editorSettingCacheManager.getEditorSettings(),
                    onPageChange = onPageChange
                )
            }

        val history = remember {
            History(page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState).apply { registerObservers() }
        }


        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                if (bookId != null)
                    exportToLinkedFile(exportEngine, bookId, appRepository.bookRepository)
                page.disposeOldPage()
            }
        }

        // TODO put in editorSetting class
        LaunchedEffect(
            editorState.isToolbarOpen,
            editorState.pen,
            editorState.penSettings,
            editorState.mode,
            editorState.isToolbarOpen,
            editorState.eraser
        ) {
            Log.i(TAG, "EditorView: saving")
            editorSettingCacheManager.setEditorSettings(
                EditorSettingCacheManager.EditorSettings(
                    isToolbarOpen = editorState.isToolbarOpen,
                    mode = editorState.mode,
                    pen = editorState.pen,
                    eraser = editorState.eraser,
                    penSettings = editorState.penSettings
                )
            )
        }



        InkaTheme {
            EditorGestureReceiver(controlTower = editorControlTower)
            EditorSurface(
                appRepository = appRepository,
                state = editorState,
                page = page,
                history = history
            )
            SelectedBitmap(
                context = context,
                controlTower = editorControlTower
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(state = editorState)
            }
            PositionedToolbar(exportEngine,navController, appRepository, editorState, editorControlTower)
            HorizontalScrollIndicator(state = editorState)
        }
    }
}


@Composable
fun PositionedToolbar(
    exportEngine: ExportEngine,
    navController: NavController,
    appRepository: AppRepository,
    editorState: EditorState,
    editorControlTower: EditorControlTower
) {
    val position = GlobalAppSettings.current.toolbarPosition

    when (position) {
        AppSettings.Position.Top -> {
            Toolbar(
                exportEngine,
                navController, appRepository, editorState, editorControlTower
            )
        }

        AppSettings.Position.Bottom -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Toolbar(exportEngine, navController, appRepository, editorState, editorControlTower)
            }
        }
    }
}