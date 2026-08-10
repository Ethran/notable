package com.ethran.notable.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.Pane
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope


// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

@SuppressLint("ViewConstructor") // we never execute constructor from XML
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val viewModel: EditorViewModel,
    /**
     * The panes drawn into this surface. One today; the list exists so a second does not require
     * restructuring. All panes share this one surface because the Onyx firmware permits exactly
     * one raw-drawing owner per process.
     */
    val panes: List<Pane>,
) : SurfaceView(context) {
    private val log = ShipBook.getLogger("DrawCanvas")

    /**
     * The pane input is currently directed at. Writing in a pane will make it active once stroke
     * routing lands; with a single pane it is simply that pane.
     */
    var activePane: Pane = panes.first()
        private set

    val page: PageView
        get() = activePane.page

    val history: History
        get() = activePane.history

    /**
     * Direct subsequent input at [pane]. Called when a stroke is routed, so the pane you last
     * wrote in becomes the one the toolbar and history act on.
     */
    fun focusPane(pane: Pane) {
        if (pane !in panes || pane === activePane) return
        log.d("Active pane changed")
        activePane = pane
    }

    private fun isStylusOrEraser(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun hasAnyStylusPointer(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { index -> isStylusOrEraser(event.getToolType(index)) }

    // Overriding dispatchTouchEvent catches the event BEFORE it is routed
    // to onTouchEvent or sent down to nested Android components.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
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
    val refreshManager = CanvasRefreshManager(this, page, viewModel, inputHandler.touchHelper)


    private val observers = CanvasObserverRegistry(
        coroutineScope, this, page, viewModel, history, inputHandler, refreshManager
    )

    fun registerObservers() = observers.registerAll()

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
                // Single pane: it covers the whole surface. With two, each gets its slice and the
                // divider sits between them.
                panes.forEach { it.layout(Rect(0, 0, width, height)) }

                // Only act if actual dimensions changed
                if (page.viewWidth == width && page.viewHeight == height) return

                log.v("Surface dimension changed!")

                // Update page dimensions, redraw and refresh
                page.updateDimensions(width, height)
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