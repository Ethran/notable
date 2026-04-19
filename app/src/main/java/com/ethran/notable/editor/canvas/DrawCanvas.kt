package com.ethran.notable.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import java.lang.Thread.sleep


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

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


            if (!DeviceCompat.isOnyxDevice || inputHandler.isErasing) {
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

    private val strokeHistoryBatch = mutableListOf<String>()
    internal fun commitToHistory() {
        if (strokeHistoryBatch.isNotEmpty()) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(strokeHistoryBatch.map { it })
            )
        )
        strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        drawCanvasToView(null)
    }


    val inputHandler =
        OnyxInputHandler(this, page, viewModel, history, coroutineScope, strokeHistoryBatch)
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
                // Restore the correct stroke size and style, now its done in initFromPersistedSettings
//                inputHandler.updatePenAndStroke()
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


    fun drawCanvasToView(dirtyRect: Rect?) {
        post {
            val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
            var canvas: Canvas? = null
            try {
                log.d("Attempting to lock canvas for zone: $zoneToRedraw on thread: ${Thread.currentThread().name}")
                // Lock the canvas only for the dirtyRect region
                canvas = this.holder.lockCanvas(zoneToRedraw)
                if (canvas == null) {
                    log.e("FAILED to lock canvas! Surface is likely invalid, destroyed, or locked by another thread.")
                    return@post
                }
                sleep(100) //for debugging, but I'm not sure if it increases chance for a bug
                canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())

                if (viewModel.toolbarState.value.mode == Mode.Select) {
                    // render selection, but only within dirtyRect
                    viewModel.selectionState.firstPageCut?.let { cutPoints ->
                        log.i("render cut")
                        val path = pointsToPath(cutPoints.map {
                            SimplePointF(
                                it.x - page.scroll.x, it.y - page.scroll.y
                            )
                        })
                        canvas.drawPath(path, selectPaint)
                    }
                }
            } catch (e: IllegalStateException) {
                log.w("Surface released during draw", e)
                // ignore — surface is gone
            } finally {
                try {
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)
                    }
                } catch (e: IllegalStateException) {
                    log.w("Surface released during unlock", e)
                }
            }
        }
    }


    fun restoreCanvas(dirtyRect: Rect, bitmap: Bitmap = page.windowedBitmap) {
        post {
            val holder = this@DrawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                // Draw the preview bitmap scaled to fit the dirty rect
                surfaceCanvas.drawBitmap(bitmap, dirtyRect, dirtyRect, null)
            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // Trigger partial refresh
                refreshScreenRegion(this@DrawCanvas, dirtyRect)
            }
        }
    }

}