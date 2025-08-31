package com.ethran.notable.editor.drawing

import android.graphics.Color
import android.graphics.Rect
import android.opengl.GLES20
import android.util.Log
import com.ethran.notable.TAG
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.refreshScreenRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.concurrent.thread


class LineRenderer {

    var isInitialized = false

    private var vertexShader: Int = -1

    private var fragmentShader: Int = -1

    private var glProgram: Int = -1

    private var positionHandle: Int = -1

    private var mvpMatrixHandle: Int = -1

    private var colorHandle: Int = -1

    //private val colorArray = FloatArray(4)

    private var vertexBuffer: FloatBuffer? = null

    private val lineCoords = FloatArray(LINE_COORDS_SIZE)


    fun initialize() {
        release()
        vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        glProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(glProgram, vertexShader)
        GLES20.glAttachShader(glProgram, fragmentShader)
        GLES20.glLinkProgram(glProgram)
        val bb: ByteBuffer =
            ByteBuffer.allocateDirect( // (number of coordinate values * 4 bytes per float)
                LINE_COORDS_SIZE * FLOAT_BYTE_SIZE
            )
        // use the device hardware's native byte order
        bb.order(ByteOrder.nativeOrder())
        // create a floating point buffer from the ByteBuffer
        vertexBuffer = bb.asFloatBuffer().apply {
            put(lineCoords)
            position(0)
        }
        positionHandle = GLES20.glGetAttribLocation(glProgram, V_POSITION)
        mvpMatrixHandle = GLES20.glGetUniformLocation(glProgram, U_MVP_MATRIX)
        colorHandle = GLES20.glGetUniformLocation(glProgram, V_COLOR)

        isInitialized = true
    }

    fun release() {
        if (vertexShader != -1) {
            GLES20.glDeleteShader(vertexShader)
            vertexShader = -1
        }
        if (fragmentShader != -1) {
            GLES20.glDeleteShader(fragmentShader)
            fragmentShader = -1
        }
        if (glProgram != -1) {
            GLES20.glDeleteProgram(glProgram)
            glProgram = -1
        }
    }

    private val colorArray = FloatArray(4)
    private var dirtyRect = Rect()
    fun drawSimpleLine(
        mvpMatrix: FloatArray,
        points: List<StrokePoint>,
        color: Color,
        viewModel: DrawCanvas
    ) {
        Log.d("LineRenderer", "drawSimpleLine")
        GLES20.glUseProgram(glProgram)
        GLES20.glLineWidth(40.0f)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // Convert Android color to GL Color
        colorArray[0] = color.red()
        colorArray[1] = color.green()
        colorArray[2] = color.blue()
        colorArray[3] = color.alpha()

        // Set color for drawing the line
        GLES20.glUniform4fv(colorHandle, 1, colorArray, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        vertexBuffer?.let { buffer ->
            val p1 = points[0]
            val p2 = points[1]

            val lineCoords = floatArrayOf(
                p1.x, p1.y, 0f,
                p2.x , p2.y, 0f
            )
            buffer.put(lineCoords)
            buffer.position(0)

            // Prepare the triangle coordinate data
            GLES20.glVertexAttribPointer(
                positionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, buffer
            )
            // Render
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, VERTEX_COUNT)
            val margin = 20
            dirtyRect = Rect(
                p1.x.toInt() - margin,
                p1.y.toInt() - margin,
                p1.x.toInt() + margin,
                p1.y.toInt() + margin
            )

            GLES20.glDisableVertexAttribArray(positionHandle)
//  TODO: Address copilot suggestion:
//   Creating a new thread for each refresh operation could lead to thread creation overhead and
//   potential race conditions. Consider using a shared thread pool or coroutine dispatcher instead.
            thread {
                refreshScreenRegion(viewModel, dirtyRect) }
        }
        GLES20.glDisableVertexAttribArray(positionHandle)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG,"GL error: $error")
        }
    }


    fun drawLine(
        mvpMatrix: FloatArray,
        points: List<StrokePoint>,
        color: Color,
        viewModel: DrawCanvas
    ) {
        GLES20.glUseProgram(glProgram)
        GLES20.glLineWidth(30.0f)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val colorArray = FloatArray(4)
        // Convert Android color to GL Color
        colorArray[0] = color.red()
        colorArray[1] = color.green()
        colorArray[2] = color.blue()
        colorArray[3] = color.alpha()

        // Set color for drawing the line
        GLES20.glUniform4fv(colorHandle, 1, colorArray, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        vertexBuffer?.let { buffer ->
            for (i in 0 until points.size - 1) {

                val p1 = points[i]
                val p2 = points[i + 1]

                val lineCoords = floatArrayOf(
                    p1.x, p1.y, 0f,
                    p2.x, p2.y, 0f
                )
                buffer.put(lineCoords)
                buffer.position(0)

                // Prepare the triangle coordinate data
                GLES20.glVertexAttribPointer(
                    positionHandle, COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT, false,
                    VERTEX_STRIDE, buffer
                )
                // Render
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, VERTEX_COUNT)
                val dirtyRect = Rect(
                    p1.x.toInt() - 20,
                    p1.y.toInt() - 20,
                    p1.x.toInt() + 20,
                    p1.y.toInt() + 20
                )

                GLES20.glDisableVertexAttribArray(positionHandle)
//                EpdController.handwritingRepaint(viewModel, dirtyRect);
                refreshScreenRegion(viewModel, dirtyRect)

            }
        }
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    companion object {
        const val COORDS_PER_VERTEX = 3
        const val LINE_COORDS_SIZE = 6
        const val FLOAT_BYTE_SIZE = 4

        private const val VERTEX_COUNT: Int = LINE_COORDS_SIZE / COORDS_PER_VERTEX
        private const val VERTEX_STRIDE: Int =
            COORDS_PER_VERTEX * FLOAT_BYTE_SIZE // 4 bytes per vertex
        private const val U_MVP_MATRIX = "uMVPMatrix"
        private const val V_POSITION = "vPosition"
        private const val VERTEX_SHADER_CODE =
            """
            uniform mat4 $U_MVP_MATRIX;
            attribute vec4 $V_POSITION;
            void main() { // the matrix must be included as a modifier of gl_Position
              gl_Position = $U_MVP_MATRIX * $V_POSITION;
            }
            """
        private const val V_COLOR = "vColor"
        private const val FRAGMENT_SHADER_CODE =
            """
            precision mediump float;
            uniform vec4 $V_COLOR;
            void main() {
              gl_FragColor = $V_COLOR;
            }                
            """

        fun loadShader(type: Int, shaderCode: String?): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}