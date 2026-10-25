package com.ethran.notable.editor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.drawing.onyx.OnyxStrokeRenderer
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * With every renderer, the highlighter tints the page without hiding the ink under it, stays one
 * shade where it overlaps itself, and is drawn wherever the page says it is — not only inside the
 * first screen.
 */
@RunWith(AndroidJUnit4::class)
class HighlighterRenderingTest {

    private val yellow = 0xFFFFFF00.toInt()
    private val renderers = listOf(AppStrokeRenderer, OnyxStrokeRenderer)

    private fun highlighter(points: List<StrokePoint>) = stroke(Pen.MARKER, yellow, 40f, points)

    private fun stroke(pen: Pen, color: Int, size: Float, points: List<StrokePoint>) = Stroke(
        size = size,
        pen = pen,
        color = color,
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

    /** A vertical run of points, one every 10px. */
    private fun column(from: Float, to: Float, x: Float): List<StrokePoint> =
        (0..((to - from) / 10f).toInt()).map { StrokePoint(x = x, y = from + it * 10f) }

    private fun StrokeRenderer.render(
        vararg strokes: Stroke,
        offset: Offset = Offset.Zero,
        zoom: Float = 1f,
    ): Bitmap {
        val bitmap = createBitmap(400, 400)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(zoom, zoom)
        strokes.forEach { drawStroke(canvas, it, offset) }
        return bitmap
    }

    private val StrokeRenderer.name get() = this::class.simpleName

    /** Issue #309: a stroke at page coordinates past the canvas, brought into view by scrolling. */
    @Test
    fun highlighterPastTheFirstScreen_isDrawn() {
        val stroke = highlighter(line(from = 50f, to = 350f, y = 1200f))
        for (renderer in renderers) {
            val bitmap = renderer.render(stroke, offset = Offset(0f, -1100f))
            assertNotEquals(
                "${renderer.name}: page y=1200 scrolled by 1100 shows at y=100",
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
                "${renderer.name}: page x=600 at zoom 0.5 shows at x=300",
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
            val name = renderer.name
            assertEquals("$name: red stays saturated", 255, Color.red(pixel))
            assertEquals("$name: green stays saturated", 255, Color.green(pixel))
            assertTrue(
                "$name: white shows through the blue channel, was ${Color.blue(pixel)}",
                Color.blue(pixel) in 50..230
            )
        }
    }

    /** A band crossing a band of the same colour is no darker than either. */
    @Test
    fun overlappingHighlighter_staysOneShade() {
        val across = highlighter(line(from = 50f, to = 350f, y = 200f))
        val down = highlighter(column(from = 50f, to = 350f, x = 200f))
        for (renderer in renderers) {
            val bitmap = renderer.render(across, down)
            assertEquals(
                "${renderer.name}: the crossing matches a single band",
                bitmap.getPixel(100, 200), bitmap.getPixel(200, 200)
            )
        }
    }

    /** Ink under the highlighter keeps its colour, though the highlighter is drawn after it. */
    @Test
    fun inkUnderHighlighter_staysBlack() {
        val ink = stroke(Pen.BALLPEN, Color.BLACK, 10f, line(from = 50f, to = 350f, y = 200f))
        val band = highlighter(line(from = 50f, to = 350f, y = 200f))
        for (renderer in renderers) {
            assertEquals(
                "${renderer.name}: ink under the band",
                Color.BLACK, renderer.render(ink, band).getPixel(200, 200)
            )
        }
    }

    /** PDF export draws on a PDF canvas; the band still tints the page and leaves the ink black. */
    @Test
    fun highlighterInPdf_tintsAndLeavesInkBlack() {
        val ink = stroke(Pen.BALLPEN, Color.BLACK, 10f, line(from = 50f, to = 350f, y = 200f))
        val band = highlighter(line(from = 50f, to = 350f, y = 200f))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "highlighter-rendering-test.pdf")
        for (renderer in renderers) {
            val bitmap = try {
                val document = PdfDocument()
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(400, 400, 1).create())
                    renderer.drawStroke(page.canvas, ink, Offset.Zero)
                    renderer.drawStroke(page.canvas, band, Offset.Zero)
                    document.finishPage(page)
                    file.outputStream().use { document.writeTo(it) }
                } finally {
                    document.close()
                }
                createBitmap(400, 400).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfRenderer(fd).use { pdf ->
                        pdf.openPage(0).use {
                            it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } finally {
                file.delete()
            }

            val beside = bitmap.getPixel(200, 185)
            assertTrue(
                "${renderer.name}: band beside the ink is tinted yellow, was ${Integer.toHexString(beside)}",
                Color.red(beside) > 240 && Color.green(beside) > 240 && Color.blue(beside) in 50..230
            )
            val onInk = bitmap.getPixel(200, 200)
            assertTrue(
                "${renderer.name}: ink under the band stays black, was ${Integer.toHexString(onInk)}",
                Color.red(onInk) < 40 && Color.green(onInk) < 40 && Color.blue(onInk) < 40
            )
        }
    }
}
