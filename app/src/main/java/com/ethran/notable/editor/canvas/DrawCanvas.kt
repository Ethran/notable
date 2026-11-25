package com.ethran.notable.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope


@SuppressLint("ViewConstructor") // we never execute constructor from XML
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val viewModel: EditorViewModel,
    val page: PageView,
    val history: History
) : SurfaceView(context) {
    private val log = ShipBook.getLogger("DrawCanvas")

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


            // With an Onyx pen the firmware renders pen and eraser strokes; without one (another
            // device, or TouchHelper failed to start) the OpenGL front-buffer renderer does.
            if (inputHandler.penDevice == null) {
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
        private set

    val inputHandler =
        OnyxInputHandler(this, page, viewModel, history, coroutineScope)

    private val surfaceWriter = SurfaceWriter(this, page, viewModel)

    /**
     * The only owner of this surface's content and, with an Onyx pen, of raw drawing. Without
     * one it drives [NoPenDevice] under the same surface rules.
     */
    val rawDrawing: RawDrawingController<Bitmap> = RawDrawingController(
        device = inputHandler.penDevice ?: NoPenDevice,
        wanted = { inputHandler.penDevice != null && viewModel.toolbarState.value.isDrawing },
        armPen = inputHandler::armPen,
        writeSurface = { area, preview, force -> surfaceWriter.write(area?.toRect(), preview, force) },
        timer = ScopeMainTimer(coroutineScope),
        postSettleMillis = DeviceCompat::postSettleMillis,
        eraseSettleMillis = DeviceCompat::eraseSettleMillis,
    )

    val refreshManager = CanvasRefreshManager(this, page, rawDrawing)


    private val observers = CanvasObserverRegistry(
        coroutineScope, this, page, viewModel, history, inputHandler, refreshManager
    )

    fun registerObservers() = observers.registerAll()

    /**
     * Registered once, for the life of the view: Android destroys the surface when the app goes to
     * the background and creates it again on this same view when it comes back.
     */
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            log.i("surface created $holder")
            // A new session over the surface: it shows the whole page, then the pen comes on.
            inputHandler.updateActiveSurface()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder, format: Int, width: Int, height: Int
        ) {
            // Only act if actual dimensions changed
            if (page.viewWidth == width && page.viewHeight == height) return

            log.v("Surface dimension changed!")

            // Update page dimensions, redraw and refresh
            page.updateDimensions(width, height)
            inputHandler.updateActiveSurface()
            onSurfaceChanged(this@DrawCanvas)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            log.i("surface destroyed ${this@DrawCanvas.hashCode()}")
            // Closes the session of this canvas's own TouchHelper; surfaceCreated reopens it.
            rawDrawing.closeSession()
        }
    }

    init {
        holder.addCallback(surfaceCallback)
    }

    /**
     * (Re)creates the OpenGL front-buffer renderer on this view, releasing the previous one: when
     * the view is created, and after the activity restarts.
     */
    fun attachRenderer() {
        log.i("Attaching the OpenGL renderer")
        glRenderer.release()
        glRenderer = OpenGLRenderer(this)
        glRenderer.attachSurfaceView(this)
    }
}
