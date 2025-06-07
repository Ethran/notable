package com.ethran.notable.views

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
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.classes.PageView
import com.ethran.notable.components.EditorGestureReceiver
import com.ethran.notable.components.EditorSurface
import com.ethran.notable.components.ScrollIndicator
import com.ethran.notable.components.SelectedBitmap
import com.ethran.notable.components.Toolbar
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.convertDpToPixel
import io.shipbook.shipbooksdk.Log


@OptIn(ExperimentalComposeUiApi::class)
@Composable
@ExperimentalFoundationApi
fun EditorView(
    navController: NavController, bookId: String?, pageId: String
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // control if we do have a page
    if (AppRepository(context).pageRepository.getById(pageId) == null) {
        if (bookId != null) {
            // clean the book
            Log.i(TAG, "Cleaning book")
            AppRepository(context).bookRepository.removePage(bookId, pageId)
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
                id = pageId,
                width = width,
                viewWidth = width,
                viewHeight = height
            )
        }


        // Dynamically update the page width when the Box constraints change
        LaunchedEffect(width, height) {
            if (page.width != width || page.viewHeight != height) {
                page.updateDimensions(width, height)
                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        val editorState =
            remember { EditorState(bookId = bookId, pageId = pageId, pageView = page) }

        val history = remember {
            History(scope, page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState)
        }

        val appRepository = AppRepository(context)

        // update opened page
        LaunchedEffect(Unit) {
            if (bookId != null) {
                appRepository.bookRepository.setOpenPageId(bookId, pageId)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                page.onDispose()
            }
        }

        // TODO put in editorSetting class
        LaunchedEffect(
            editorState.isToolbarOpen,
            editorState.pen,
            editorState.penSettings,
            editorState.mode
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

        val lastRoute = navController.previousBackStackEntry

        fun goToNextPage() {
            if (bookId != null) {
                val newPageId = appRepository.getNextPageIdFromBookAndPageOrCreate(
                    pageId = pageId, notebookId = bookId
                )
                navController.navigate("books/${bookId}/pages/${newPageId}") {
                    popUpTo(lastRoute!!.destination.id) {
                        inclusive = false
                    }
                }
            }
        }

        fun goToPreviousPage() {
            if (bookId != null) {
                val newPageId = appRepository.getPreviousPageIdFromBookAndPage(
                    pageId = pageId, notebookId = bookId
                )
                if (newPageId != null) navController.navigate("books/${bookId}/pages/${newPageId}")
            }
        }

        val toolbarPosition = GlobalAppSettings.current.toolbarPosition

        InkaTheme {
            EditorSurface(
                state = editorState, page = page, history = history
            )
            EditorGestureReceiver(
                goToNextPage = ::goToNextPage,
                goToPreviousPage = ::goToPreviousPage,
                controlTower = editorControlTower,
                state = editorState
            )
            SelectedBitmap(
                context = context,
                editorState = editorState,
                controlTower = editorControlTower
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(context = context, state = editorState)
            }
            // Toolbar at Top or Bottom
            when (toolbarPosition) {
                AppSettings.Position.Top -> {
                    Toolbar(
                        navController = navController,
                        state = editorState,
                        controlTower = editorControlTower
                    )
                }

                AppSettings.Position.Bottom -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) { //this fixes this
                        Spacer(modifier = Modifier.weight(1f))
                        // Top/center content here
                        Toolbar(
                            navController = navController,
                            state = editorState,
                            controlTower = editorControlTower
                        )
                    }
                }
            }

        }
    }
}


