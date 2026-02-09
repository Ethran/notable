package com.ethran.notable.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.ethran.notable.editor.utils.selectImage
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import com.ethran.notable.editor.utils.setAnimationMode
import com.ethran.notable.editor.utils.toPageCoordinates
import com.ethran.notable.io.uriToBitmap
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.extension.isNotNull
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
    private val strokeHistoryBatch = mutableListOf<String>()
    private val log = ShipBook.getLogger("DrawCanvas")

    override fun onTouchEvent(event: MotionEvent): Boolean { //Custom view DrawCanvas overrides onTouchEvent but not performClick
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        // We will only capture stylus events, and past rest down
//        log.d("onTouchEvent, ${event.getToolType(0)}")
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
            return if (!DeviceCompat.isOnyxDevice || isErasing) glRenderer.onTouchListener.onTouch(
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

    var isErasing: Boolean = false

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


    private val observers = CanvasObserverRegistry(
        coroutineScope, this, page, state, history, inputHandler
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


    /**
     * handles selection, and decide if we should exit the animation mode
     */
    suspend fun selectRectangle(rectToSelect: Rect) {
        val inPageCoordinates = toPageCoordinates(rectToSelect, page.zoomLevel.value, page.scroll)

        val imagesToSelect =
            PageDataManager.getImagesInRectangle(inPageCoordinates, page.currentPageId)
        val strokesToSelect =
            PageDataManager.getStrokesInRectangle(inPageCoordinates, page.currentPageId)
        if (imagesToSelect.isNotNull() && strokesToSelect.isNotNull()) {
            CanvasEventBus.rectangleToSelectByGesture.value = null
            if (imagesToSelect.isNotEmpty() || strokesToSelect.isNotEmpty()) {
                selectImagesAndStrokes(coroutineScope, page, state, imagesToSelect, strokesToSelect)
            } else {
                setAnimationMode(false)
                SnackState.globalSnackFlow.emit(
                    SnackConf(
                        text = "There isn't anything.",
                        duration = 3000,
                    )
                )
            }
        } else SnackState.globalSnackFlow.emit(
            SnackConf(
                text = "Page is empty!",
                duration = 3000,
            )
        )

    }

    // TODO: move it
    fun commitToHistory() {
        if (strokeHistoryBatch.isNotEmpty()) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(strokeHistoryBatch.map { it })
            )
        )
        strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        drawCanvasToView(null)
    }

    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoomLevel.value}")

        // post what page drawn to visible surface
        drawCanvasToView(dirtyRect)
        if (CanvasEventBus.drawingInProgress.isLocked) log.w("Drawing is still in progress there might be a bug.")

        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            log.w("Not in drawing mode, skipping unfreezing")
            return
        }
        // reset screen freeze
        resetScreenFreeze(inputHandler.touchHelper)
    }

    suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!state.isDrawing) {
            waitForDrawing()
            drawCanvasToView(null)
            log.w("Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            log.w(
                "refreshUiSuspend() is called from the main thread."
            )
            logCallStack("refreshUiSuspend_main_thread")
        } else log.v(
            "refreshUiSuspend() is called from the non-main thread."
        )
        waitForDrawing()
        drawCanvasToView(null)
        resetScreenFreeze(inputHandler.touchHelper)
    }

    fun handleImage(imageUri: Uri) {
        // Convert the image to a software-backed bitmap
        val imageBitmap = uriToBitmap(context, imageUri)?.asImageBitmap()
        if (imageBitmap == null) showHint(
            "There was an error during image processing.", coroutineScope
        )
        val softwareBitmap = imageBitmap?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, true)
        if (softwareBitmap != null) {
            CanvasEventBus.addImageByUri.value = null

            // Get the image dimensions
            val imageWidth = softwareBitmap.width
            val imageHeight = softwareBitmap.height

            // Calculate the center position for the image relative to the page dimensions
            val centerX = (page.viewWidth - imageWidth) / 2 + page.scroll.x.toInt()
            val centerY = (page.viewHeight - imageHeight) / 2 + page.scroll.y.toInt()
            val imageToSave = Image(
                x = centerX,
                y = centerY,
                height = imageHeight,
                width = imageWidth,
                uri = imageUri.toString(),
                pageId = page.currentPageId
            )
            drawImage(
                context, page.windowedCanvas, imageToSave, -page.scroll
            )
            selectImage(coroutineScope, page, state, imageToSave)
            // image will be added to database when released, the same as with paste element.
            state.selectionState.placementMode = PlacementMode.Paste
            // make sure, that after regaining focus, we wont go back to drawing mode
        } else {
            // Handle cases where the bitmap could not be created
            Log.e("ImageProcessing", "Failed to create software bitmap from URI.")
        }
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