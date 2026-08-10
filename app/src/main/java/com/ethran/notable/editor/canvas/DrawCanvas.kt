package com.ethran.notable.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.Pane
import com.ethran.notable.editor.PaneGroup
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

/**
 * Dead space between panes, in pixels. Ink is rejected here — the firmware clips the live preview
 * to each limit rect, and [routeStrokeToPane] drops strokes that start in the gap. Wide enough to
 * be an unambiguous target, narrow enough not to waste a 10" panel.
 */
const val PANE_GUTTER_PX = 24

@SuppressLint("ViewConstructor") // we never execute constructor from XML
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val viewModel: EditorViewModel,
    /**
     * The panes drawn into this surface, and which one has focus. All panes share this one surface
     * because the Onyx firmware permits exactly one raw-drawing owner per process.
     */
    val paneGroup: PaneGroup,
) : SurfaceView(context) {
    private val log = ShipBook.getLogger("DrawCanvas")

    val panes: List<Pane> get() = paneGroup.panes

    /** The pane input is directed at. Owned by [paneGroup] so the toolbar can see it too. */
    val activePane: Pane get() = paneGroup.active

    val page: PageView
        get() = activePane.page

    val history: History
        get() = activePane.history

    /**
     * Direct subsequent input at [pane]. Called when a stroke is routed, so the pane you last
     * wrote in becomes the one the toolbar and history act on.
     */
    fun focusPane(pane: Pane) {
        if (pane === activePane) return
        log.d("Active pane changed")
        val previous = activePane
        if (!paneGroup.focus(pane)) return
        // Re-arm raw drawing over the newly active pane; the limit rect follows focus.
        inputHandler.updateActiveSurface()

        // The repaint happens inside updateActiveSurface, after the re-arm has finished. Doing it
        // here would be wiped: re-arming resets the EPD layer, and it completes asynchronously.
    }

    private fun isStylusOrEraser(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun hasAnyStylusPointer(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { index -> isStylusOrEraser(event.getToolType(index)) }

    // Overriding dispatchTouchEvent catches the event BEFORE it is routed
    // to onTouchEvent or sent down to nested Android components.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 0. A touch landing in an inactive pane activates it rather than drawing into it.
        //
        // Raw drawing is armed over the active pane only, so the firmware does not consume input
        // over the others — it arrives here as an ordinary MotionEvent, for pen and finger alike.
        // Switching on DOWN means the gesture that selects a pane never also marks it.
        // Fingers only. The firmware consumes stylus input inside its limit rects, but NOT outside
        // them — so a pen-down in an inactive pane does arrive here as an ordinary MotionEvent and
        // would otherwise switch focus. Focus is meant to change deliberately: inferring it from
        // ink means a brush against the wrong pane both switches panes and marks the document.
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            panes.size > 1 &&
            !hasAnyStylusPointer(event)
        ) {
            val target = paneGroup.paneAt(event.x, event.y)
            if (target != null && target !== activePane) {
                log.i("Touch in inactive pane — bringing it to the front")
                // In practice this is a finger: while raw drawing is armed the firmware consumes
                // stylus input, so a pen-down never reaches here. That is the intended design —
                // focus changes deliberately, and the pen only ever writes into the active pane.
                // Focus moves, and the stroke is then allowed to proceed normally: routing runs
                // after this, by which point the target pane is active, so the ink lands where it
                // was drawn. The event is deliberately NOT consumed.
                //
                // Previously this consumed the event and flagged the next raw stroke to be dropped,
                // so the activating gesture would not also mark the pane. But the firmware does not
                // always deliver a raw stroke for a consumed touch, and the flag then survived to
                // eat the next legitimate stroke — losing ink in the already-active pane.
                // Losing a stroke the user meant to write is worse than an occasional unintended
                // switch, and a stray mark can at least be undone.
                focusPane(target)
            }
        }

        // 1. Accessibility & Clicks
        if (event.actionMasked == MotionEvent.ACTION_UP && !hasAnyStylusPointer(event)) {
            performClick()
        }

        // 2. Intercept at the highest level if a stylus is present
        if (hasAnyStylusPointer(event)) {
            // Block parent scrolling
            parent?.requestDisallowInterceptTouchEvent(true)


            // NATIVE ERASER INDICATOR:
            // On Onyx devices the eraser stroke is now rendered natively by the firmware
            // (see einkHelper.setupSurface -> setEraserRawDrawingEnabled), so we no longer
            // route erase touches into the OpenGL front-buffer renderer. Non-Onyx devices
            // still use OpenGL as their only renderer. The original condition is kept
            // (commented) as a reference. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            // if (!DeviceCompat.isOnyxDevice || inputHandler.isErasing) {
            if (!DeviceCompat.isOnyxDevice) {
                glRenderer.onTouchListener.onTouch(this, event)
            }

            // Consume completely. This prevents Compose underneath from ever
            // seeing this event IF the stylus was the first thing to touch the screen.
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    @Suppress("RedundantOverride")
    override fun performClick(): Boolean {
        return super.performClick()
    }

    var glRenderer = OpenGLRenderer(this)

    internal fun commitToHistory() {
        if (activePane.strokeHistoryBatch.isNotEmpty()) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(activePane.strokeHistoryBatch.map { it })
            )
        )
        activePane.strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        refreshManager.drawCanvasToView(null)
    }


    val inputHandler = OnyxInputHandler(this, viewModel, coroutineScope)
    val refreshManager = CanvasRefreshManager(this, viewModel, inputHandler.touchHelper)


    /**
     * One registry per pane.
     *
     * Signals are per-pane (see [PaneEventBus]), so a single registry bound to one page hears only
     * that page's refreshes, reloads and history commits. With two panes that meant the second
     * pane never repainted its own strokes — they sat committed but unrendered until something else
     * forced a redraw — and undo only ever reached the first pane's history.
     */
    private val observers = mutableMapOf<Pane, CanvasObserverRegistry>()

    fun registerObservers() = syncPanes()

    /**
     * Bring per-pane observers, layout and the EPD arming into line with [PaneGroup.panes].
     *
     * Idempotent, and called from the `AndroidView` update block, so it runs on every recomposition
     * and does nothing unless the pane set actually changed.
     *
     * This exists because the canvas must **survive** a pane-set change. Splitting originally
     * rebuilt `DrawCanvas` under a Compose `key`, which got observer disposal ordering right for
     * free but destroyed the `SurfaceView`. A device trace showed the replacement surface was not
     * allocated for six seconds — until a rotation forced it — and the screen was blank throughout.
     * Keeping the canvas means doing by hand what the key was doing: dropping observers for panes
     * that went away, adding them for panes that arrived.
     */
    fun syncPanes() {
        val current = paneGroup.panes
        if (observers.keys == current.toSet()) return
        log.i("Pane set changed: ${observers.size} -> ${current.size}")

        // Drop departed panes first, so a page reused by an arriving pane is not left holding a
        // cancelled registry's entry in the shared activeObserverJobs map.
        (observers.keys - current.toSet()).forEach { gone ->
            observers.remove(gone)?.cancelAll()
        }
        current.filterNot { it in observers }.forEach { added ->
            observers[added] = CanvasObserverRegistry(
                coroutineScope, this, added, added.page, viewModel,
                added.history, inputHandler, refreshManager
            ).also { it.registerAll() }
        }

        // Nothing to lay out until the surface exists; surfaceChanged does it then.
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        layoutPanes(surfaceWidth, surfaceHeight)

        // The limit rects follow the pane count, so raw drawing has to be re-armed. The repaint
        // happens inside updateActiveSurface, after the re-arm completes — doing it here would be
        // wiped, since re-arming resets the EPD layer asynchronously.
        inputHandler.updateActiveSurface()
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * Divide the surface between the panes and tell each one its size.
     *
     * A single pane owns the whole surface — a degenerate case, not a special one. Two panes split
     * it evenly with [PANE_GUTTER_PX] of dead space between them. Returns true if any pane's
     * geometry changed.
     *
     * Vertical splitting is not built: the device is used in landscape, where side-by-side is what
     * fits two documents. `Pane.screenRect` is a plain Rect, so nothing here precludes it.
     */
    private fun layoutPanes(width: Int, height: Int): Boolean {
        val rects = when (panes.size) {
            0 -> return false
            1 -> listOf(Rect(0, 0, width, height))
            else -> {
                val half = width / 2
                val g = PANE_GUTTER_PX / 2
                listOf(
                    Rect(0, 0, half - g, height),
                    Rect(half + g, 0, width, height),
                )
            }
        }
        if (panes.size > rects.size) {
            log.w("${panes.size} panes but only ${rects.size} slices; extra panes will not be laid out")
        }

        var changed = false
        val resized = mutableListOf<Pane>()
        panes.zip(rects).forEach { (pane, rect) ->
            if (pane.screenRect != rect) changed = true
            pane.layout(rect)
            // Each pane's page renders at the pane's size, not the surface's.
            val sizeChanged =
                pane.page.viewWidth != rect.width() || pane.page.viewHeight != rect.height()
            pane.page.updateDimensions(rect.width(), rect.height())
            if (sizeChanged) resized.add(pane)
        }

        // updateDimensions recreates the window bitmap and reloads only the background, so a
        // resized pane comes back blank until its strokes are redrawn. Splitting the surface
        // resizes every pane at once, so every one of them needs that redraw — miss it and a pane
        // simply shows nothing, with no error anywhere.
        //
        // Drawn directly rather than via pane.events.forceUpdate: that flow has no replay and no
        // buffer, so an emit with no subscriber attached yet is silently dropped. During layout the
        // registries may not be collecting, which is exactly how the left pane ended up blank while
        // being blitted correctly from a correctly sized bitmap.
        resized.forEach { pane ->
            val p = pane.page
            p.drawAreaScreenCoordinates(Rect(0, 0, p.viewWidth, p.viewHeight))
        }
        if (resized.isNotEmpty()) refreshManager.drawCanvasToView(null)
        return changed
    }

    fun init() {
        log.i("Initializing Canvas")
        glRenderer = OpenGLRenderer(this@DrawCanvas)
        glRenderer.attachSurfaceView(this)


        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.i("surface created $holder")
                // set up the drawing surface
                inputHandler.updateActiveSurface()
                // The surface is only drawable once this callback fires; any
                // refreshUi attempts before this silently no-op (lockCanvas
                // returns null). Paint now so the newly-created surface
                // isn't left blank.
                this@DrawCanvas.post { refreshManager.refreshUi(null) }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                // Lay panes out BEFORE the unchanged-dimensions check below. PageView is built
                // with the same dimensions the surface ends up with, so that check usually short
                // circuits — and a pane whose screenRect was never set has an empty rect, which
                // makes the blit clip everything away and the screen come up blank.
                val relaidOut = layoutPanes(width, height)

                // Only act further if the surface itself actually changed size
                if (!relaidOut && surfaceWidth == width && surfaceHeight == height) return
                surfaceWidth = width
                surfaceHeight = height

                log.v("Surface dimension changed!")
                inputHandler.updateActiveSurface()
                onSurfaceChanged(this@DrawCanvas)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                log.i(
                    "surface destroyed ${
                        this@DrawCanvas.hashCode()
                    } - ref $referencedSurfaceView"
                )
                holder.removeCallback(this)
                if (referencedSurfaceView == this@DrawCanvas.hashCode().toString()) {
                    inputHandler.touchHelper?.closeRawDrawing()
                }
                onSurfaceDestroy(this@DrawCanvas, inputHandler.touchHelper)
            }
        }

        this.holder.addCallback(surfaceCallback)

    }

}