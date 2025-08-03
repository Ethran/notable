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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    val appRepository = remember { AppRepository(context) }

    // control if we do have a page
    if (appRepository.pageRepository.getById(pageId) == null) {
        if (bookId != null) {
            // clean the book
            Log.i(TAG, "Cleaning book")
            appRepository.bookRepository.removePage(bookId, pageId)
        }
        navController.navigate("library")
        return
    }
    var currentPageId by remember { mutableStateOf(pageId) }

    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()


        val page = remember(currentPageId) {
            PageView(
                context = context,
                coroutineScope = scope,
                id = currentPageId,
                width = width,
                viewWidth = width,
                viewHeight = height
            )
        }

        val editorState =
            remember { EditorState(bookId = bookId, pageId = currentPageId, pageView = page) }

        val history = remember {
            History(scope, page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState)
        }

        // update opened page
        LaunchedEffect(currentPageId) {
            if (bookId != null) {
                appRepository.bookRepository.setOpenPageId(bookId, currentPageId)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                page.disposeOldPage()
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

        fun goToNextPage(): String? {
            return if (bookId != null) {
                val newPageId = appRepository.getNextPageIdFromBookAndPageOrCreate(
                    pageId = currentPageId, notebookId = bookId
                )
                currentPageId = newPageId
                newPageId
            } else
                null
        }

        fun goToPreviousPage(): String? {
            return if (bookId != null) {
                val newPageId = appRepository.getPreviousPageIdFromBookAndPage(
                    pageId = currentPageId, notebookId = bookId
                )
                if (newPageId != null)
                    currentPageId = newPageId
                newPageId
            } else null
        }

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
            PositionedToolbar(navController, editorState, editorControlTower)
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