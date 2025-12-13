package com.ethran.notable.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
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
import com.ethran.notable.editor.ui.EditorGestureReceiver
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.toolbar.Toolbar
import com.ethran.notable.io.exportToLinkedFile
import com.ethran.notable.sync.SyncEngine
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@OptIn(ExperimentalComposeUiApi::class)
@Composable
@ExperimentalFoundationApi
fun EditorView(
    navController: NavController, bookId: String?, pageId: String, onPageChange: (String) -> Unit
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()
    val appRepository = remember { AppRepository(context) }

    // control if we do have a page
    if (appRepository.pageRepository.getById(pageId) == null) {
        if (bookId != null) {
            // clean the book
            Log.i(TAG, "Could not find page, Cleaning book")
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    text = "Could not find page, cleaning book",
                    duration = 4000
                )
            )
            appRepository.bookRepository.removePage(bookId, pageId)
        }
        navController.navigate("library")
        return
    }

    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()


        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                currentPageId = pageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager
            )
        }

        val editorState =
            remember {
                EditorState(
                    bookId = bookId,
                    pageId = pageId,
                    pageView = page,
                    appRepository,
                    onPageChange
                )
            }

        val history = remember {
            History(page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState, context).apply { registerObservers() }
        }


        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                exportToLinkedFile(context, bookId, appRepository.bookRepository)
                page.disposeOldPage()

                // Trigger sync on note close if enabled
                val settings = GlobalAppSettings.current
                if (settings.syncSettings.syncEnabled && settings.syncSettings.syncOnNoteClose && bookId != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            SyncLogger.i("EditorSync", "Auto-syncing on editor close")
                            SyncEngine(context).syncNotebook(bookId)
                        } catch (e: Exception) {
                            SyncLogger.e("EditorSync", "Auto-sync failed: ${e.message}")
                        }
                    }
                }
            }
        }

        // TODO put in editorSetting class
        LaunchedEffect(
            editorState.isToolbarOpen,
            editorState.pen,
            editorState.penSettings,
            editorState.mode,
            editorState.eraser
        ) {
            Log.i(TAG, "EditorView: saving")
            EditorSettingCacheManager.setEditorSettings(
                context,
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
            EditorSurface(
                state = editorState, page = page, history = history
            )
            EditorGestureReceiver(controlTower = editorControlTower)
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
            PositionedToolbar(navController, editorState, editorControlTower)
            HorizontalScrollIndicator(state = editorState)
        }
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PositionedToolbar(
    navController: NavController, editorState: EditorState, editorControlTower: EditorControlTower
) {
    val position = GlobalAppSettings.current.toolbarPosition

    when (position) {
        AppSettings.Position.Top -> {
            Toolbar(navController, editorState, editorControlTower)
        }

        AppSettings.Position.Bottom -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Toolbar(navController, editorState, editorControlTower)
            }
        }
    }
}