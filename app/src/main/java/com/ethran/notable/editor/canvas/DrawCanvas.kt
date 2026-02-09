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
import com.ethran.notable.editor.canvas.OnyxInputHandler
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

@SuppressLint("ViewConstructor") // we never execute constructor from XML
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val state: EditorState,
    val page: PageView,
    val history: History
) : SurfaceView(context) {
    private val log = ShipBook.getLogger("DrawCanvas")

    override fun onTouchEvent(event: MotionEvent): Boolean { //Custom view DrawCanvas overrides onTouchEvent but not performClick
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        // We will only capture stylus events, and past rest down
//        log.d("onTouchEvent, ${event.getToolType(0)}")
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
            return if (!DeviceCompat.isOnyxDevice || inputHandler.isErasing) glRenderer.onTouchListener.onTouch(
                this, event
            )
            else true
        }
        // Pass everything else down
        return super.onTouchEvent(event)
    }

    @Suppress("RedundantOverride")
    override fun performClick(): Boolean {
        return super.performClick()
    }
    var glRenderer = OpenGLRenderer(this)

    companion object {

        suspend fun waitForDrawing() {
            Log.d(
                "DrawCanvas.waitForDrawing", "waiting"
            )
            val elapsed = measureTimeMillis {
                withTimeoutOrNull(3000) {
                    // Just to make sure wait 1ms before checking lock.
                    delay(1)
                    // Wait until drawingInProgress is unlocked before proceeding
                    while (CanvasEventBus.drawingInProgress.isLocked) {
                        delay(5)
                    }
                } ?: Log.e(
                    "DrawCanvas.waitForDrawing",
                    "Timeout while waiting for drawing lock. Potential deadlock."
                )

            }
            when {
                elapsed > 3000 -> Log.e(
                    "DrawCanvas.waitForDrawing", "Exceeded timeout ($elapsed ms)"
                )

                elapsed > 100 -> Log.w("DrawCanvas.waitForDrawing", "Took too long: $elapsed ms")
                else -> Log.d("DrawCanvas.waitForDrawing", "Finished waiting in $elapsed ms")
            }

        }

        suspend fun waitForDrawingWithSnack() {
            if (CanvasEventBus.drawingInProgress.isLocked) {
                val snack = SnackConf(text = "Waiting for drawing to finish…", duration = 60000)
                SnackState.globalSnackFlow.emit(snack)
                waitForDrawing()
                SnackState.cancelGlobalSnack.emit(snack.id)
            }
        }
    }

    fun getActualState(): EditorState {
        return this.state
    }


    val inputHandler = OnyxInputHandler(this, page, state, history, coroutineScope)
    val refreshManager = CanvasRefreshManager(this, page, state, inputHandler.touchHelper)


    private val observers = CanvasObserverRegistry(
        coroutineScope, this, page, state, history, inputHandler, refreshManager
    )

    fun registerObservers() = observers.registerAll()

    fun init() {
        log.i("Initializing Canvas")
        glRenderer.release()
        glRenderer = OpenGLRenderer(this@DrawCanvas)
        glRenderer.attachSurfaceView(this)


        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.i("surface created $holder")
                // set up the drawing surface
                inputHandler.updateActiveSurface()
                // Restore the correct stroke size and style.
                inputHandler.updatePenAndStroke()
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
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        var canvas: Canvas? = null
        try {
            // Lock the canvas only for the dirtyRect region
            canvas = this.holder.lockCanvas(zoneToRedraw) ?: return

            canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())

            if (getActualState().mode == Mode.Select) {
                // render selection, but only within dirtyRect
                getActualState().selectionState.firstPageCut?.let { cutPoints ->
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