package com.ethran.notable.editor.canvas

import android.graphics.Rect
import androidx.compose.runtime.snapshotFlow
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.ImageHandler
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.loadPreview
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.selectRectangle
import com.ethran.notable.editor.utils.waitForEpdRefresh
import com.onyx.android.sdk.extension.isNull
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CanvasObserverRegistry(
    private val coroutineScope: CoroutineScope,
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val state: EditorState,
    private val history: History,
    private val inputHandler: OnyxInputHandler,
    private val refreshManager: CanvasRefreshManager
) {
    private val logCanvasObserver = ShipBook.getLogger("CanvasObservers")
    fun registerAll() {
        ImageHandler(drawCanvas.context, page, state, coroutineScope).observeImageUri()

        observeRefreshUiImmediately()
        observeForceUpdate()
        observeRefreshUi()
        observeFocusChange()
        observeZoomLevel()
        observeDrawingState()
        observeSelectionGesture()
        observeClearPage()
        observeRestartAfterConfChange()
        observePenChanges()
        observeIsDrawingSnapshot()
        observeToolbar()
        observeMode()
        observeHistory()
        observeSaveCurrent()
        observeQuickNav()
        observeRestoreCanvas()
    }

    private fun observeRefreshUiImmediately() {
        coroutineScope.launch {
            CanvasEventBus.refreshUiImmediately.collect {
                logCanvasObserver.v("Refreshing UI!")
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                refreshManager.refreshUi(zoneToRedraw)
            }
        }
    }

    private fun observeForceUpdate() {
        // observe forceUpdate, takes rect in screen coordinates
        // given null it will redraw whole page
        // BE CAREFUL: partial update is not tested fairly -- might not work in some situations.
        coroutineScope.launch(Dispatchers.Main.immediate) {
            CanvasEventBus.forceUpdate.collect { dirtyRectangle ->
                // On loading, make sure that the loaded strokes are visible to it.
                logCanvasObserver.v("Force update, zone: $dirtyRectangle, Strokes to draw: ${page.strokes.size}")
                val zoneToRedraw = dirtyRectangle ?: Rect(0, 0, page.viewWidth, page.viewHeight)
                page.drawAreaScreenCoordinates(zoneToRedraw)
                launch(Dispatchers.Default) {
                    if (dirtyRectangle.isNull()) refreshManager.refreshUiSuspend()
                    else {
                        partialRefreshRegionOnce(drawCanvas, zoneToRedraw, inputHandler.touchHelper)
                    }
                }
            }
        }
    }

    private fun observeRefreshUi() {
        coroutineScope.launch(Dispatchers.Default) {
            CanvasEventBus.refreshUi.collect {
                logCanvasObserver.v("Refreshing UI!")
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeFocusChange() {
        coroutineScope.launch {
            CanvasEventBus.onFocusChange.collect { hasFocus ->
                logCanvasObserver.v("App has focus: $hasFocus")
                if (hasFocus) {
                    state.checkForSelectionsAndMenus()
                    inputHandler.updatePenAndStroke() // The setting might been changed by other app.
                    drawCanvas.drawCanvasToView(null)
                } else {
                    CanvasEventBus.isDrawing.emit(false)
                }
            }
        }
    }

    private fun observeZoomLevel() {
        coroutineScope.launch {
            page.zoomLevel.drop(1).collect {
                logCanvasObserver.v("zoom level change: ${page.zoomLevel.value}")
                PageDataManager.setPageZoom(page.currentPageId, page.zoomLevel.value)
                inputHandler.updatePenAndStroke()
            }
        }
    }

    private fun observeDrawingState() {
        coroutineScope.launch {
            CanvasEventBus.isDrawing.collect {
                logCanvasObserver.v("drawing state changed to $it!")
                state.isDrawing = it
            }
        }
    }

    private fun observeSelectionGesture() {
        coroutineScope.launch {
            CanvasEventBus.rectangleToSelectByGesture.drop(1).collect {
                if (it != null) {
                    logCanvasObserver.v("Area to Select (screen): $it")
                    selectRectangle(page, drawCanvas.coroutineScope, state, it)
                }
            }
        }
    }

    private fun observeClearPage() {
        coroutineScope.launch {
            CanvasEventBus.clearPageSignal.collect {
                require(!state.isDrawing) { "Cannot clear page in drawing mode" }
                logCanvasObserver.v("Clear page signal!")
                cleanAllStrokes(page, history)
            }
        }
    }

    private fun observeRestartAfterConfChange() {
        coroutineScope.launch {
            CanvasEventBus.restartAfterConfChange.collect {
                logCanvasObserver.v("Configuration changed!")
                drawCanvas.init()
                drawCanvas.drawCanvasToView(null)
            }
        }
    }

    private fun observePenChanges() {
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                logCanvasObserver.v("pen change: ${state.pen}")
                inputHandler.updatePenAndStroke()
                refreshManager.refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                logCanvasObserver.v("pen settings change: ${state.penSettings}")
                inputHandler.updatePenAndStroke()
                refreshManager.refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                logCanvasObserver.v("eraser change: ${state.eraser}")
                inputHandler.updatePenAndStroke()
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeIsDrawingSnapshot() {
        coroutineScope.launch {
            snapshotFlow { state.isDrawing }.drop(1).collect {
                logCanvasObserver.v("isDrawing change to $it")
                // We need to close all menus
                if (it) {
//                    logCallStack("Closing all menus")
                    state.closeAllMenus()
//                    EpdController.waitForUpdateFinished() // it does not work.
                    waitForEpdRefresh()
                }
                inputHandler.updateIsDrawing()
            }
        }
    }

    private fun observeToolbar() {
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                logCanvasObserver.v("istoolbaropen change: ${state.isToolbarOpen}")
                inputHandler.updateActiveSurface()
                inputHandler.updatePenAndStroke()
                refreshManager.refreshUi(null)
            }
        }
    }

    private fun observeMode() {
        coroutineScope.launch {
            snapshotFlow { drawCanvas.getActualState().mode }.drop(1).collect {
                logCanvasObserver.v("mode change: ${drawCanvas.getActualState().mode}")
                inputHandler.updatePenAndStroke()
                refreshManager.refreshUiSuspend()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeHistory() {
        coroutineScope.launch {
            // After 500ms add to history strokes
            CanvasEventBus.commitHistorySignal.debounce(500).collect {
                logCanvasObserver.v("Commiting to history")
                drawCanvas.commitToHistory()
            }
        }
        coroutineScope.launch {
            CanvasEventBus.commitHistorySignalImmediately.collect {
                drawCanvas.commitToHistory()
                CanvasEventBus.commitCompletion.complete(Unit)
            }
        }
    }


    private fun observeSaveCurrent() {
        coroutineScope.launch {
            CanvasEventBus.saveCurrent.collect {
                // Push current bitmap to persist layer so preview has something to load
                PageDataManager.cacheBitmap(page.currentPageId, page.windowedBitmap)
                PageDataManager.saveTopic.tryEmit(page.currentPageId)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQuickNav() {
        coroutineScope.launch {
            CanvasEventBus.previewPage.debounce(50).collectLatest { pageId ->
                val pageNumber =
                    AppRepository(drawCanvas.context).getPageNumber(page.pageFromDb?.notebookId!!, pageId)
                Log.d("QuickNav", "Previewing page($pageNumber): $pageId")

                val previewBitmap = withContext(Dispatchers.IO) {
                    loadPreview(
                        context = drawCanvas.context,
                        pageIdToLoad = pageId,
                        expectedWidth = page.viewWidth,
                        expectedHeight = page.viewHeight,
                        pageNumber = pageNumber
                    )
                }

                if (previewBitmap.isRecycled) {
                    Log.e("QuickNav", "Failed to preview page for $pageId, skipping draw")
                    return@collectLatest
                }

                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.restoreCanvas(zoneToRedraw, previewBitmap)
            }
        }
    }

    private fun observeRestoreCanvas() {
        coroutineScope.launch {
            CanvasEventBus.restoreCanvas.collect {
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.restoreCanvas(zoneToRedraw)
            }
        }
    }


}


