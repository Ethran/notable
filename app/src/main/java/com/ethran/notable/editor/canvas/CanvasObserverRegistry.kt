package com.ethran.notable.editor.canvas

import android.graphics.Rect
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.ImageHandler
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.loadPreview
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.selectRectangle
import com.ethran.notable.editor.utils.waitForEpdRefresh
import com.onyx.android.sdk.extension.isNull
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CanvasObserverRegistry(
    private val coroutineScope: CoroutineScope,
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val inputHandler: OnyxInputHandler,
    private val refreshManager: CanvasRefreshManager
) {
    private val log = ShipBook.getLogger("CanvasObservers")
    private val pageDataManager = page.pageDataManager

    fun registerAll() {
        ImageHandler(drawCanvas.context, page, viewModel, coroutineScope).observeImageUri()

        observeRefreshUiImmediately()
        observeForceUpdate()
        observeRefreshUi()
        observeFocusChange()
        observeZoomLevel()
        observeDrawingState()
        observeSelectionGesture()
        observeClearPage()
        observeRestartAfterConfChange()
        observeReloadFromDb()
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
                log.v("Refreshing UI!")
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
                log.v("Force update, zone: $dirtyRectangle, Strokes to draw: ${page.strokes.size}")
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
                log.v("Refreshing UI!")
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeFocusChange() {
        coroutineScope.launch {
            CanvasEventBus.onFocusChange.collect { hasFocus ->
                log.v("App has focus: $hasFocus")
                if (hasFocus) {
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
                log.v("zoom level change: ${page.zoomLevel.value}")
                pageDataManager.setPageZoom(page.currentPageId, page.zoomLevel.value)
                inputHandler.updatePenAndStroke()
            }
        }
    }

    private fun observeDrawingState() {
        coroutineScope.launch {
            CanvasEventBus.isDrawing.collect {
                log.v("drawing state changed to $it!")
                viewModel.setDrawingStateFromCanvas(it)
            }
        }
    }

    private fun observeSelectionGesture() {
        coroutineScope.launch {
            CanvasEventBus.rectangleToSelectByGesture.drop(1).collect {
                if (it != null) {
                    log.v("Area to Select (screen): $it")
                    selectRectangle(page, drawCanvas.coroutineScope, viewModel, it)
                }
            }
        }
    }

    private fun observeClearPage() {
        coroutineScope.launch {
            CanvasEventBus.clearPageSignal.collect {
                log.v("Clear page signal!")
                cleanAllStrokes(page, history)
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeRestartAfterConfChange() {
        coroutineScope.launch {
            CanvasEventBus.reinitSignal.collect {
                log.v("Configuration changed!")
                drawCanvas.init()
                drawCanvas.drawCanvasToView(null)
            }
        }
    }

    private fun observeReloadFromDb() {
        coroutineScope.launch {
            CanvasEventBus.reloadFromDb.collect {
                page.refreshCurrentPage()
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observePenChanges() {
        coroutineScope.launch(Dispatchers.Default) {
            viewModel.toolbarState
                .map { it.pen }
                .distinctUntilChanged()
                .collect { pen ->
                    log.v("pen change: $pen")
                    inputHandler.updatePenAndStroke()
                    //I think we don't need to refresh the screen here.
//                refreshManager.refreshUiSuspend()
                }
        }
        coroutineScope.launch {
            viewModel.toolbarState
                .map { it.penSettings }
                .distinctUntilChanged()
                .drop(1)
                .collect { penSettings ->
                    log.v("pen settings change: $penSettings")
                    inputHandler.updatePenAndStroke()
                    refreshManager.refreshUiSuspend()
                }
        }
        coroutineScope.launch {
            viewModel.toolbarState
                .map { it.eraser }
                .distinctUntilChanged()
                .drop(1)
                .collect { eraser ->
                    log.v("eraser change: $eraser")
                    inputHandler.updatePenAndStroke()
                    refreshManager.refreshUiSuspend()
                }
        }
    }

    private fun observeIsDrawingSnapshot() {
        coroutineScope.launch {
            viewModel.toolbarState
                .map { it.isDrawing }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    log.v("isDrawing change to $it")
                    // We need to close all menus
                    if (it) {
                        CanvasEventBus.closeMenusSignal.emit(Unit)
                        waitForEpdRefresh()
                    }
                    inputHandler.updateIsDrawing()
                }
        }
    }

    private fun observeToolbar() {
        coroutineScope.launch {
            viewModel.toolbarState
                .map { it.isToolbarOpen }
                .distinctUntilChanged()
                .drop(1)
                .collect { isToolbarOpen ->
                    log.v("istoolbaropen change: $isToolbarOpen")
                    inputHandler.updateActiveSurface()
                    inputHandler.updatePenAndStroke()
                    refreshManager.refreshUi(null)
                }
        }
    }

    private fun observeMode() {
        coroutineScope.launch {
            viewModel.toolbarState
                .map { it.mode }
                .distinctUntilChanged()
                .drop(1)
                .collect { mode ->
                    log.v("mode change: $mode")
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
                log.v("Commiting to history")
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
                pageDataManager.cacheBitmap(page.currentPageId, page.windowedBitmap)
                pageDataManager.saveTopic.tryEmit(page.currentPageId)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQuickNav() {
        coroutineScope.launch {
            CanvasEventBus.previewPage.debounce(50).collectLatest { pageId ->
                val pageNumber = pageDataManager.getPageNumberInCurrentNotebook(pageId)
                val pageUpdatedAtMs = pageDataManager.getPageUpdatedAt(pageId)

                val previewBitmap = withContext(Dispatchers.IO) {
                    loadPreview(
                        context = drawCanvas.context,
                        pageIdToLoad = pageId,
                        expectedWidth = page.viewWidth,
                        expectedHeight = page.viewHeight,
                        pageNumber = pageNumber,
                        requireExactMatch = false,
                        pageUpdatedAtMs = pageUpdatedAtMs
                    )
                }

                if (previewBitmap.isRecycled) {
                    log.e("Failed to preview page for $pageId, skipping draw")
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
                log.d("Restoring canvas")
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.restoreCanvas(zoneToRedraw)
                log.v("Restored canvas")
            }
        }
    }


}
