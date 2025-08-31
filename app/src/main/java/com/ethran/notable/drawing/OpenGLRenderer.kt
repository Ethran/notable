package com.ethran.notable.drawing


import android.annotation.SuppressLint
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.annotation.WorkerThread
import androidx.core.graphics.toColor
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import androidx.input.motionprediction.MotionEventPredictor
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.Timing
import com.ethran.notable.data.db.StrokePoint
import io.shipbook.shipbooksdk.Log


class OpenGLRenderer(
    private var viewModel: DrawCanvas
) : GLFrontBufferedRenderer.Callback<StrokePoint> {
    private val mvpMatrix = FloatArray(16)
    private val projection = FloatArray(16)

    var frontBufferRenderer: GLFrontBufferedRenderer<StrokePoint>? = null
    private var motionEventPredictor: MotionEventPredictor? = null

    private var lineRenderer: LineRenderer = LineRenderer()

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var currentX: Float = 0f
    private var currentY: Float = 0f

    @WorkerThread // GLThread
    private fun obtainRenderer(): LineRenderer =
        if (lineRenderer.isInitialized) {
            lineRenderer
        } else {
            lineRenderer
                .apply {
                    initialize()
                }
        }

    val openGlPoints2 = mutableListOf<StrokePoint>()

    fun clearPointBuffer() {
        openGlPoints2.clear()
    }


    private var timeOfLastRefresh2 = 0L
    override fun onDrawFrontBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        param: StrokePoint
    ) {
        val timer = Timing("onDrawFrontBufferedLayer")

        val bufferWidth = bufferInfo.width
        val bufferHeight = bufferInfo.height
        GLES20.glViewport(0, 0, bufferWidth, bufferHeight)
        // Map Android coordinates to GL coordinates
        Matrix.orthoM(
            mvpMatrix,
            0,
            0f,
            bufferWidth.toFloat(),
            0f,
            bufferHeight.toFloat(),
            -1f,
            1f
        )

        Matrix.multiplyMM(projection, 0, mvpMatrix, 0, transform, 0)

        openGlPoints2.add(param)
        if (openGlPoints2.size < 2)
            return
        if (System.currentTimeMillis() - timeOfLastRefresh2 < 16)
            return
        val pointsToDraw = openGlPoints2.toList()
        openGlPoints2.clear()
        openGlPoints2.add(pointsToDraw.last())

        timeOfLastRefresh2 = System.currentTimeMillis()

        timer.step("obtainRenderer")

        obtainRenderer().drawSimpleLine(projection, pointsToDraw, Color.BLACK.toColor(), viewModel)

        timer.end("drawLine")
    }

    override fun onDrawMultiBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        params: Collection<StrokePoint>
    ) {
        val timer = Timing("onDrawMultiBufferedLayer")

        val bufferWidth = bufferInfo.width
        val bufferHeight = bufferInfo.height
        // define the size of the rectangle for rendering
        GLES20.glViewport(0, 0, bufferWidth, bufferHeight)
        // Computes the ModelViewProjection Matrix
        Matrix.orthoM(
            mvpMatrix,
            0,
            0f,
            bufferWidth.toFloat(),
            0f,
            bufferHeight.toFloat(),
            -1f,
            1f
        )
        // perform matrix multiplication to transform the Android data to OpenGL reference
        Matrix.multiplyMM(projection, 0, mvpMatrix, 0, transform, 0)

        // Clear the screen with black
//        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
//        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (openGlPoints2.size < 2)
            return
        if (System.currentTimeMillis() - timeOfLastRefresh2 < 16)
            return
        val pointsToDraw = openGlPoints2.toList()
        openGlPoints2.clear()
        openGlPoints2.add(pointsToDraw.last())

        timeOfLastRefresh2 = System.currentTimeMillis()
        timer.step("obtainRenderer")

        // Render the entire scene (all lines)
        obtainRenderer().drawSimpleLine(projection, pointsToDraw, Color.BLACK.toColor(), viewModel)
        timer.end("drawLine")


    }

    fun attachSurfaceView(surfaceView: SurfaceView) {
        if (isAttached)
            Log.e("OpenGLRenderer", "Already attached")
        frontBufferRenderer = GLFrontBufferedRenderer(surfaceView, this)
        motionEventPredictor = MotionEventPredictor.newInstance(surfaceView)
    }
    val isAttached: Boolean
        get() = frontBufferRenderer != null

    fun release() {
        frontBufferRenderer?.release(true)
        {
            obtainRenderer().release()
        }
    }

    private fun getStrokePoint(motionEvent: MotionEvent): StrokePoint {
        val tilt = motionEvent.getAxisValue(MotionEvent.AXIS_TILT)
        val pressure = motionEvent.pressure
        val orientation = motionEvent.orientation

        return StrokePoint(
            pressure = motionEvent.pressure,
            x = motionEvent.x,
            y = motionEvent.y,
            size = 0f,
            tiltX = 0,
            tiltY = 0,
            timestamp = 0
        )
    }

    // THIS DOES NOT GET ANY EVENTS, see EditorGestureReceiver.
    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        val point = getStrokePoint(event)
        motionEventPredictor?.record(event)
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS)
            return@OnTouchListener true
        Log.d("MotionEvent", event.toString())

        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                // Ask that the input system not batch MotionEvents
                // but instead deliver them as soon as they're available
                view.requestUnbufferedDispatch(event)
                frontBufferRenderer?.renderFrontBufferedLayer(point)

            }

            MotionEvent.ACTION_MOVE -> {
                previousX = currentX
                previousY = currentY
                currentX = event.x
                currentY = event.y


                // Send the short line to front buffered layer: fast rendering
                frontBufferRenderer?.renderFrontBufferedLayer(point)
            }

            MotionEvent.ACTION_UP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (event.flags and MotionEvent.FLAG_CANCELED) == MotionEvent.FLAG_CANCELED
                ) {
                    frontBufferRenderer?.cancel()
                } else {
                    frontBufferRenderer?.commit()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                frontBufferRenderer?.cancel()
            }

        }
        true
    }
}