package com.ethran.notable.editor.canvas

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.ImageHandler
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.loadPagePreviewOrFallback
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.selectRectangle
import com.onyx.android.sdk.extension.isNull
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
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

    private var registered = false

    // All observers launch on this scope (a child of the supplied Compose scope) instead of
    // directly on coroutineScope, so they can be cancelled as a group. The supplied Compose
    // scope is long-lived and shared, so observers launched on it directly would outlive a
    // recreated DrawCanvas and keep collecting — duplicating every refresh.
    private val observerJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val observerScope = CoroutineScope(coroutineScope.coroutineContext + observerJob)

    companion object {
        // The observer job of the currently active registry. A newly registering registry
        // cancels the previous one, guaranteeing exactly one live observer set even if an old
        // DrawCanvas instance leaked.
        private var activeObserverJob: Job? = null
    }

    fun registerAll() {
        // Guard against double registration on the same instance.
        if (registered) {
            log.w("registerAll called twice — observers already registered, skipping")
            return
        }
        registered = true
        // Cancel observers from any prior (possibly leaked) registry so refreshes aren't doubled.
        activeObserverJob?.cancel()
        activeObserverJob = observerJob
        // NOTE: Be careful with the dispatchers, choose them wisely.

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

    // Last (scroll, zoom) actually pushed by an immediate refresh. Used to drop redundant
    // refreshes — see observeRefreshUiImmediately.
    private var lastImmediateScroll: Offset? = null
    private var lastImmediateZoom: Float = Float.NaN

    private fun observeRefreshUiImmediately() {
        observerScope.launch(Dispatchers.Main) {
            // conflate() collapses any backlog that builds while a refresh is mid-flight.
            CanvasEventBus.refreshUiImmediately.conflate().collect {
                // Dedup by content: the collector drains in bursts on Main, so several scroll
                // steps that settled to the same scroll get drained back-to-back. Pushing the
                // identical bitmap to the EPD (and freezing the screen) again is wasted work, so
                // skip when scroll+zoom are unchanged. Select mode is exempt: page-cut drag
                // changes content without moving scroll.
                val scroll = page.scroll
                val zoom = page.zoomLevel.value
                val inSelect = viewModel.toolbarState.value.mode == Mode.Select
                if (!inSelect && scroll == lastImmediateScroll && zoom == lastImmediateZoom) {
                    log.v("Refreshing UI! skipped — unchanged (scroll=$scroll, zoom=$zoom)")
                    return@collect
                }
                lastImmediateScroll = scroll
                lastImmediateZoom = zoom
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
        observerScope.launch(Dispatchers.Main) {
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
        observerScope.launch(Dispatchers.Default) {
            CanvasEventBus.refreshUi.collect {
                log.v("Refreshing UI!")
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeFocusChange() {
        observerScope.launch {
            CanvasEventBus.onFocusChange.collect { hasFocus ->
                log.v("App has focus: $hasFocus")
                if (hasFocus) {
                    inputHandler.updatePenAndStroke() // The setting might been changed by other app.
                    drawCanvas.refreshManager.drawCanvasToView(null)
                    viewModel.updateDrawingState()
                } else {
                    CanvasEventBus.isDrawing.emit(false)
                }
            }
        }
    }

    private fun observeZoomLevel() {
        observerScope.launch {
            page.zoomLevel.drop(1).collect {
                log.v("zoom level change: ${page.zoomLevel.value}")
                pageDataManager.setPageZoom(page.currentPageId, page.zoomLevel.value)
                inputHandler.updatePenAndStroke()
            }
        }
    }

    private fun observeDrawingState() {
        observerScope.launch {
            CanvasEventBus.isDrawing.collect {
                log.v("drawing state changed to $it!")
                viewModel.setDrawingStateFromCanvas(it)
            }
        }
    }

    private fun observeSelectionGesture() {
        observerScope.launch {
            CanvasEventBus.rectangleToSelectByGesture.collect {
                log.v("Area to Select (screen): $it")
                selectRectangle(page, drawCanvas.coroutineScope, viewModel, it)
            }
        }
    }

    private fun observeClearPage() {
        observerScope.launch {
            CanvasEventBus.clearPageSignal.collect {
                log.v("Clear page signal!")
                cleanAllStrokes(page, history)
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observeRestartAfterConfChange() {
        observerScope.launch {
            CanvasEventBus.reinitSignal.collect {
                log.v("Configuration changed!")
                drawCanvas.init()
                drawCanvas.refreshManager.drawCanvasToView(null)
            }
        }
    }

    private fun observeReloadFromDb() {
        observerScope.launch {
            CanvasEventBus.reloadFromDb.collect {
                page.refreshCurrentPage()
                refreshManager.refreshUiSuspend()
            }
        }
    }

    private fun observePenChanges() {
        observerScope.launch(Dispatchers.Default) {
            viewModel.toolbarState
                // Preset id, not just the base pen type: switching between two ballpen
                // presets changes color/size without changing `pen`.
                .map { it.pen to it.penPresetId }
                .distinctUntilChanged()
                .collect { pen ->
                    log.v("pen change: $pen")
                    inputHandler.updatePenAndStroke()
                    //I think we don't need to refresh the screen here.
//                refreshManager.refreshUiSuspend()
                }
        }
        observerScope.launch {
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
        observerScope.launch {
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
        observerScope.launch {
            viewModel.toolbarState
                .map { it.isDrawing }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    log.v("isDrawing change to $it")
                    // We need to close all menus
                    if (it) {
                        CanvasEventBus.closeMenusSignal.emit(Unit)
//                        waitForEpdRefresh()
                    }
                    inputHandler.updateIsDrawing()
                }
        }
    }

    private fun observeToolbar() {
        observerScope.launch {
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
        observerScope.launch {
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
        observerScope.launch {
            // After 500ms add to history strokes
            CanvasEventBus.commitHistorySignal.debounce(500).collect {
                log.v("Commiting to history")
                drawCanvas.commitToHistory()
            }
        }
        observerScope.launch {
            CanvasEventBus.commitHistorySignalImmediately.collect {
                drawCanvas.commitToHistory()
                CanvasEventBus.commitCompletion.complete(Unit)
            }
        }
    }


    private fun observeSaveCurrent() {
        observerScope.launch {
            CanvasEventBus.saveCurrent.collect {
                // Push current bitmap to persist layer so preview has something to load
                pageDataManager.cacheBitmap(page.currentPageId, page.windowedBitmap)
                pageDataManager.saveTopic.tryEmit(page.currentPageId)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQuickNav() {
        observerScope.launch {
            CanvasEventBus.previewPage.debounce(50).collectLatest { pageId ->
                if (!CanvasEventBus.isScrubbing.value) return@collectLatest // dropped — scrub already ended
                val pageNumber = pageDataManager.getPageNumberInCurrentNotebook(pageId)
                val pageUpdatedAtMs = pageDataManager.getPageUpdatedAt(pageId)

                log.d("QuickNav IO load started for page $pageId")
                val previewBitmap = withContext(Dispatchers.IO) {
                    loadPagePreviewOrFallback(
                        context = drawCanvas.context,
                        pageIdToLoad = pageId,
                        expectedWidth = page.viewWidth,
                        expectedHeight = page.viewHeight,
                        pageNumber = pageNumber,
                        pageUpdatedAtMs = pageUpdatedAtMs
                    )
                }

                if (previewBitmap.isRecycled) {
                    log.e("Failed to preview page for $pageId, skipping draw")
                    return@collectLatest
                }

                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                if (!CanvasEventBus.isScrubbing.value) return@collectLatest // dropped — race lost
                log.d("QuickNav restoreCanvas: page=$pageId, bitmap=${previewBitmap.hashCode()}")
                drawCanvas.refreshManager.restoreCanvas(zoneToRedraw, previewBitmap)
            }
        }
    }

    private fun observeRestoreCanvas() {
        observerScope.launch {
            CanvasEventBus.restoreCanvas.collect {
                log.d("Restoring canvas")
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                drawCanvas.refreshManager.restoreCanvas(zoneToRedraw)
                log.v("Restored canvas")
            }
        }
    }


}
