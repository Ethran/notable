package com.ethran.notable.editor

import android.graphics.Rect
import androidx.compose.runtime.snapshotFlow
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.loadPreview
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.waitForEpdRefresh
import com.onyx.android.sdk.extension.isNull
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val touchHelper: TouchHelper?,
//    private val inputHandler: OnyxInputHandler,
//    private val refreshManager: CanvasRefreshManager
) {
    private val logCanvasObserver = ShipBook.getLogger("CanvasObservers")

//    fun registerAll() {
//        observeForceUpdate()
//        observeRefreshUi()
//        observeFocusChange()
//        observeZoomLevel()
//        observeDrawingState()
//        observeImageUri()
//        observeSelectionGesture()
//        observeClearPage()
//        observePenChanges()
//        observeToolbar()
//        observeMode()
//        observeHistory()
//        observeQuickNav()
//        // ...
//    }
    fun registerAll() {

        coroutineScope.launch {
            CanvasEventBus.refreshUiImmediately.collect {
                logCanvasObserver.v("Refreshing UI!")
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.refreshUi(zoneToRedraw)
            }
        }

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
                    if (dirtyRectangle.isNull()) drawCanvas.refreshUiSuspend()
                    else {
                        partialRefreshRegionOnce(drawCanvas, zoneToRedraw, touchHelper)
                    }
                }
            }
        }

        // observe refreshUi
        coroutineScope.launch(Dispatchers.Default) {
            CanvasEventBus.refreshUi.collect {
                logCanvasObserver.v("Refreshing UI!")
                drawCanvas.refreshUiSuspend()
            }
        }

        coroutineScope.launch {
            CanvasEventBus.onFocusChange.collect { hasFocus ->
                logCanvasObserver.v("App has focus: $hasFocus")
                if (hasFocus) {
                    state.checkForSelectionsAndMenus()
                    drawCanvas.updatePenAndStroke() // The setting might been changed by other app.
                    drawCanvas.drawCanvasToView(null)
                } else {
                    CanvasEventBus.isDrawing.emit(false)
                }
            }
        }
        coroutineScope.launch {
            page.zoomLevel.drop(1).collect {
                logCanvasObserver.v("zoom level change: ${page.zoomLevel.value}")
                PageDataManager.setPageZoom(page.currentPageId, page.zoomLevel.value)
                drawCanvas.updatePenAndStroke()
            }
        }

        coroutineScope.launch {
            CanvasEventBus.isDrawing.collect {
                logCanvasObserver.v("drawing state changed to $it!")
                state.isDrawing = it
            }
        }


        coroutineScope.launch {
            CanvasEventBus.addImageByUri.drop(1).collect { imageUri ->
                if (imageUri != null) {
                    logCanvasObserver.v("Received image: $imageUri")
                    drawCanvas.handleImage(imageUri)
                } //else
//                    log.i(  "Image uri is empty")
            }
        }
        coroutineScope.launch {
            CanvasEventBus.rectangleToSelectByGesture.drop(1).collect {
                if (it != null) {
                    logCanvasObserver.v("Area to Select (screen): $it")
                    drawCanvas.selectRectangle(it)
                }
            }
        }

        coroutineScope.launch {
            CanvasEventBus.clearPageSignal.collect {
                require(!state.isDrawing) { "Cannot clear page in drawing mode" }
                logCanvasObserver.v("Clear page signal!")
                cleanAllStrokes(page, history)
            }
        }

        coroutineScope.launch {
            CanvasEventBus.restartAfterConfChange.collect {
                logCanvasObserver.v("Configuration changed!")
                drawCanvas.init()
                drawCanvas.drawCanvasToView(null)
            }
        }

        // observe pen and stroke size
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                logCanvasObserver.v("pen change: ${state.pen}")
                drawCanvas.updatePenAndStroke()
                drawCanvas.refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                logCanvasObserver.v("pen settings change: ${state.penSettings}")
                drawCanvas.updatePenAndStroke()
                drawCanvas.refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                logCanvasObserver.v("eraser change: ${state.eraser}")
                drawCanvas.updatePenAndStroke()
                drawCanvas.refreshUiSuspend()
            }
        }

        // observe is drawing
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
                drawCanvas.updateIsDrawing()
            }
        }

        // observe toolbar open
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                logCanvasObserver.v("istoolbaropen change: ${state.isToolbarOpen}")
                drawCanvas.updateActiveSurface()
                drawCanvas.updatePenAndStroke()
                drawCanvas.refreshUi(null)
            }
        }

        // observe mode
        coroutineScope.launch {
            snapshotFlow { drawCanvas.getActualState().mode }.drop(1).collect {
                logCanvasObserver.v("mode change: ${drawCanvas.getActualState().mode}")
                drawCanvas.updatePenAndStroke()
                drawCanvas.refreshUiSuspend()
            }
        }

        coroutineScope.launch {
            //After 500ms add to history strokes
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


        coroutineScope.launch {
            CanvasEventBus.saveCurrent.collect {
                // Push current bitmap to persist layer so preview has something to load
                PageDataManager.cacheBitmap(page.currentPageId, page.windowedBitmap)
                PageDataManager.saveTopic.tryEmit(page.currentPageId)
            }
        }

        coroutineScope.launch {
            CanvasEventBus.previewPage.debounce(50).collectLatest { pageId ->
                val pageNumber =
                    AppRepository(drawCanvas.context).getPageNumber(page.pageFromDb?.notebookId!!, pageId)
                Log.d("QuickNav", "Previewing page($pageNumber): $pageId")
                // Load and prepare a preview bitmap sized for the visible view area (IO thread)
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

        coroutineScope.launch {
            CanvasEventBus.restoreCanvas.collect {
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.restoreCanvas(zoneToRedraw)
            }
        }

    }
}


