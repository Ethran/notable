package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.PageViewport
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.ImageHandler
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.loadPagePreviewOrFallback
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
        observeResume()
        observeZoom()
        observeClearPage()
        observeRestartAfterConfChange()
        observeReloadFromDb()
        observePenChanges()
        observeIsDrawing()
        observeToolbar()
        observeMode()
        observeQuickNav()
        observeScrubEnd()
    }

    // Last bitmap and viewport actually pushed by an immediate refresh. Used to drop redundant
    // refreshes — see observeRefreshUiImmediately.
    private var lastImmediate: Pair<Bitmap, PageViewport>? = null

    private fun observeRefreshUiImmediately() {
        observerScope.launch(Dispatchers.Main) {
            // conflate() collapses any backlog that builds while a refresh is mid-flight.
            CanvasEventBus.refreshUiImmediately.conflate().collect {
                // Dedup by content: the collector drains in bursts on Main, so several scroll
                // steps that settled to the same scroll get drained back-to-back. Pushing the
                // identical bitmap to the EPD (and freezing the screen) again is wasted work, so
                // skip when the bitmap and the viewport are unchanged; two pages at the same
                // viewport differ only in their bitmap. Select mode is exempt: page-cut drag
                // changes content without moving scroll.
                val shown = page.windowedBitmap to page.viewport.value
                val inSelect = viewModel.toolbarState.value.mode == Mode.Select
                if (!inSelect && shown == lastImmediate) {
                    log.v("Refreshing UI! skipped — unchanged ($shown)")
                    return@collect
                }
                lastImmediate = shown
                log.v("Refreshing UI!")
                refreshManager.refreshUi(null)
            }
        }
    }

    private fun observeForceUpdate() {
        observerScope.launch(Dispatchers.Main) {
            CanvasEventBus.forceUpdate.collect {
                // On loading, make sure that the loaded strokes are visible to it.
                log.v("Force update, strokes to draw: ${page.strokes.size}")
                page.drawAreaScreenCoordinates(Rect(0, 0, page.viewWidth, page.viewHeight))
                launch(Dispatchers.Default) { refreshManager.refreshUiSuspend() }
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

    /**
     * Redraws after a resume (wake-up, return from another app) or a focus regain (a popup, dialog
     * or another window closed): the panel may show something else by then.
     */
    private fun observeResume() {
        observerScope.launch {
            CanvasEventBus.isResumed.drop(1).collect { resumed ->
                log.v("App resumed: $resumed")
                if (resumed) redrawIfPenStaysOff()
            }
        }
        observerScope.launch {
            CanvasEventBus.hasWindowFocus.drop(1).collect { hasFocus ->
                log.v("Window focus: $hasFocus")
                if (hasFocus) redrawIfPenStaysOff()
            }
        }
    }

    /**
     * If `isDrawing` changes, its reconcile shows the whole page, so drawing it here too would be a
     * second full-page draw. If it stays off (a popup open across a wake-up, a selection), nothing
     * else draws it. The ViewModel mirrors resume and focus inline on Main,
     * before this observer runs, so `isDrawing` is already current here.
     */
    private fun redrawIfPenStaysOff() {
        if (viewModel.toolbarState.value.isDrawing) return
        refreshManager.refreshUi(null)
    }

    private fun observeZoom() {
        observerScope.launch {
            page.viewport.map { it.zoom }.distinctUntilChanged().drop(1).collect { zoom ->
                log.v("zoom level change: $zoom")
                inputHandler.updatePenAndStroke()
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
                drawCanvas.attachRenderer()
                refreshManager.refreshUi(null)
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
        // On Main, like every other caller of updatePenAndStroke: the pen style setters must not
        // run from two threads at once.
        observerScope.launch {
            viewModel.toolbarState
                // Preset id, not just the base pen type: switching between two ballpen
                // presets changes color/size without changing `pen`.
                .map { it.pen to it.penPresetId }
                .distinctUntilChanged()
                .collect { pen ->
                    log.v("pen change: $pen")
                    inputHandler.updatePenAndStroke()
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
                    // The side-button eraser's track and the hand eraser's style both follow it.
                    inputHandler.armPen()
                    refreshManager.refreshUiSuspend()
                }
        }
    }

    private fun observeIsDrawing() {
        observerScope.launch {
            viewModel.toolbarState
                .map { it.isDrawing }
                .distinctUntilChanged()
                // Applied at registration too (one whole-page write).
                .collect {
                    val state = viewModel.toolbarState.value
                    log.d(
                        "Update is drawing: ${state.isDrawing} (resumed=${state.isResumed}, " +
                            "focus=${state.hasWindowFocus}, menu=${state.isMenuOpen}, " +
                            "eraserMenu=${state.isEraserMenuOpen}, penMenu=${state.penMenuPresetId}, " +
                            "background=${state.isBackgroundSelectorModalOpen}, " +
                            "quickNav=${state.isQuickNavOpen}, selection=${state.isSelectionActive}, " +
                            "selectionGesture=${state.isSelectionGestureActive})"
                    )
                    drawCanvas.rawDrawing.reconcile()
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
                    // The session is rebuilt over the new area: it shows the page and arms the pen.
                    inputHandler.updateActiveSurface()
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
    private fun observeQuickNav() {
        observerScope.launch {
            CanvasEventBus.previewPage.debounce(50).collectLatest { request ->
                // The preview belongs to the scrub it was asked for in; dropped once that is over.
                val (scrub, pageId) = request
                if (!CanvasEventBus.isCurrentScrub(scrub)) return@collectLatest
                // Null for a loose page, or if sync has just deleted the notebook.
                val pageNumber = page.openPage.record?.notebookId
                    ?.let { viewModel.appRepository.bookRepository.getPageIndex(it, pageId) }
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

                if (!CanvasEventBus.isCurrentScrub(scrub)) return@collectLatest
                log.d("QuickNav preview: scrub=$scrub, page=$pageId, bitmap=${previewBitmap.hashCode()}")
                refreshManager.showPreview(scrub, previewBitmap)
            }
        }
    }

    /**
     * A scrub's lifetime is its preview's: whichever way it ends (a page chosen, the same page,
     * nothing, or straight into the next scrub), its preview ends and the canvas shows its page.
     */
    private fun observeScrubEnd() {
        observerScope.launch {
            var current: Int? = null
            CanvasEventBus.activeScrub.collect { scrub ->
                val ended = current
                current = scrub
                if (ended != null) { // a StateFlow never repeats a value: this one ended
                    log.d("Scrub $ended ended")
                    refreshManager.endPreview(ended)
                }
            }
        }
    }


}
