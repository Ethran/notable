package com.ethran.notable.editor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.drawing.onyx.OnyxStrokeRenderer
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The highlighter is translucent and drawn wherever the page says it is, with every renderer —
 * not only inside the first screen.
 */
@RunWith(AndroidJUnit4::class)
class HighlighterRenderingTest {

    private val yellow = 0xFFFFFF00.toInt()
    private val renderers = listOf(AppStrokeRenderer, OnyxStrokeRenderer)

    private fun highlighter(points: List<StrokePoint>, size: Float = 40f) = Stroke(
        size = size,
        pen = Pen.MARKER,
        color = yellow,
        top = points.minOf { it.y } - size,
        bottom = points.maxOf { it.y } + size,
        left = points.minOf { it.x } - size,
        right = points.maxOf { it.x } + size,
        points = points,
        pageId = "test-page",
    )

    /** A horizontal run of points, one every 10px. */
    private fun line(from: Float, to: Float, y: Float): List<StrokePoint> =
        (0..((to - from) / 10f).toInt()).map { StrokePoint(x = from + it * 10f, y = y) }

    private fun StrokeRenderer.render(
        stroke: Stroke,
        offset: Offset = Offset.Zero,
        zoom: Float = 1f,
    ): Bitmap {
        val bitmap = createBitmap(400, 400)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(zoom, zoom)
        drawStroke(canvas, stroke, offset)
        return bitmap
    }

    /** Issue #309: a stroke at page coordinates past the canvas, brought into view by scrolling. */
    @Test
    fun highlighterPastTheFirstScreen_isDrawn() {
        val stroke = highlighter(line(from = 50f, to = 350f, y = 1200f))
        for (renderer in renderers) {
            val bitmap = renderer.render(stroke, offset = Offset(0f, -1100f))
            assertNotEquals(
                "${renderer::class.simpleName}: page y=1200 scrolled by 1100 shows at y=100",
                Color.WHITE, bitmap.getPixel(200, 100)
            )
        }
    }

    /** Zoomed out, page area past the canvas width comes into view and is drawn there too. */
    @Test
    fun highlighterPastTheCanvasWidth_isDrawnWhenZoomedOut() {
        val stroke = highlighter(line(from = 450f, to = 750f, y = 200f))
        for (renderer in renderers) {
            val bitmap = renderer.render(stroke, zoom = 0.5f)
            assertNotEquals(
                "${renderer::class.simpleName}: page x=600 at zoom 0.5 shows at x=300",
                Color.WHITE, bitmap.getPixel(300, 100)
            )
        }
    }

    /** It tints the page instead of covering it. */
    @Test
    fun highlighter_isTranslucent() {
        val stroke = highlighter(line(from = 50f, to = 350f, y = 200f))
        for (renderer in renderers) {
            val pixel = renderer.render(stroke).getPixel(200, 200)
            val name = renderer::class.simpleName
            assertEquals("$name: red stays saturated", 255, Color.red(pixel))
            assertEquals("$name: green stays saturated", 255, Color.green(pixel))
            assertTrue(
                "$name: white shows through the blue channel, was ${Color.blue(pixel)}",
                Color.blue(pixel) in 50..230
            )
        }
    }
}
