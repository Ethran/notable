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
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rendering contract for the highlighter: it is translucent, it sits behind pen strokes, its
 * overlaps stay one shade, and it is drawn wherever the page says it is — not only inside the
 * first screen.
 */
@RunWith(AndroidJUnit4::class)
class HighlighterRenderingTest {

    private val yellow = 0xFFFFFF00.toInt()
    private val black = 0xFF000000.toInt()

    private fun strokeOf(
        pen: Pen,
        color: Int,
        size: Float,
        points: List<StrokePoint>,
    ) = Stroke(
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

    /** A straight run of points, one every 10px, so no renderer's jump filter trims it. */
    private fun line(from: Float, to: Float, at: Float, horizontal: Boolean): List<StrokePoint> {
        val steps = ((to - from) / 10f).toInt()
        return (0..steps).map { i ->
            val d = from + i * 10f
            if (horizontal) StrokePoint(x = d, y = at) else StrokePoint(x = at, y = d)
        }
    }

    private fun render(
        strokes: List<Stroke>,
        offset: Offset = Offset.Zero,
        zoom: Float = 1f,
        size: Int = 400,
    ): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(zoom, zoom)
        drawStrokesLayered(canvas, strokes, offset)
        return bitmap
    }

    /**
     * Issue #309: the stroke lives at page coordinates far outside the target bitmap and is
     * brought into view by the offset. A renderer that rasterises at raw point coordinates into
     * a buffer the size of the canvas drops it entirely.
     */
    @Test
    fun highlighterPastTheFirstScreen_isDrawn() {
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 1200f, horizontal = true)
        )

        val bitmap = render(listOf(highlighter), offset = Offset(0f, -1100f))

        assertNotEquals(
            "highlighter at page y=1200 with scroll 1100 should be visible at y=100",
            Color.WHITE, bitmap.getPixel(200, 100)
        )
    }

    /** Zoomed out, page area past the canvas width comes into view and is drawn there too. */
    @Test
    fun highlighterPastTheCanvasWidth_isDrawnWhenZoomedOut() {
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 450f, to = 750f, at = 200f, horizontal = true)
        )

        val bitmap = render(listOf(highlighter), zoom = 0.5f)

        assertNotEquals(
            "highlighter at page x=600 with zoom 0.5 should be visible at x=300",
            Color.WHITE, bitmap.getPixel(300, 100)
        )
    }

    /** The layer alpha is applied to the band, so it tints the page instead of covering it. */
    @Test
    fun highlighter_isTranslucent() {
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )

        val pixel = render(listOf(highlighter)).getPixel(200, 200)

        assertEquals("yellow over white keeps red saturated", 255, Color.red(pixel))
        assertEquals("yellow over white keeps green saturated", 255, Color.green(pixel))
        assertTrue(
            "blue should land near the layer alpha's midpoint, was ${Color.blue(pixel)}",
            Color.blue(pixel) in 110..145
        )
    }

    /**
     * The layer is sized from the strokes' stored bounds, so those bounds have to leave room for
     * the band's width — sampling both edges of a 40px band catches a layer that clips it.
     */
    @Test
    fun layer_doesNotClipTheWidthOfTheBand() {
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )

        val bitmap = render(listOf(highlighter))

        assertNotEquals("top edge of the band", Color.WHITE, bitmap.getPixel(200, 182))
        assertNotEquals("bottom edge of the band", Color.WHITE, bitmap.getPixel(200, 218))
    }

    /**
     * The whole point of the single layer: two bands crossing are exactly as dark as one. A
     * per-stroke alpha would make the crossing darker.
     */
    @Test
    fun overlappingHighlighterStrokes_stayOneShade() {
        val horizontal = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )
        val vertical = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = false)
        )

        val bitmap = render(listOf(horizontal, vertical))

        assertEquals(
            "the crossing must match a single band",
            bitmap.getPixel(100, 200), bitmap.getPixel(200, 200)
        )
    }

    /** One layer means one z-position: highlighter goes behind every pen stroke. */
    @Test
    fun highlighter_isDrawnBehindPenStrokes() {
        val ink = strokeOf(
            Pen.BALLPEN, black, 10f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )
        // Added after the ink, so insertion order alone would put it on top.
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )

        val bitmap = render(listOf(ink, highlighter))

        assertEquals(
            "ink under a highlight must keep its colour",
            black, bitmap.getPixel(200, 200)
        )
    }

    /** PDF export draws on a PDF canvas, where the layer has to survive as vector content. */
    @Test
    fun highlighterInPdfExport_isTranslucentAndBehindInk() {
        val ink = strokeOf(
            Pen.BALLPEN, black, 10f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )
        val highlighter = strokeOf(
            Pen.MARKER, yellow, 40f, line(from = 50f, to = 350f, at = 200f, horizontal = true)
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "highlighter-rendering-test.pdf")

        val bitmap = try {
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(400, 400, 1).create())
            drawStrokesLayered(page.canvas, listOf(ink, highlighter), Offset.Zero)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
            document.close()

            createBitmap(400, 400).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use {
                        it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } finally {
            file.delete()
        }

        val band = bitmap.getPixel(200, 185)
        assertTrue(
            "the band beside the ink should be translucent yellow, was ${Integer.toHexString(band)}",
            Color.red(band) > 240 && Color.green(band) > 240 && Color.blue(band) in 100..155
        )
        val onInk = bitmap.getPixel(200, 200)
        assertTrue(
            "ink under a highlight must stay black, was ${Integer.toHexString(onInk)}",
            Color.red(onInk) < 30 && Color.green(onInk) < 30 && Color.blue(onInk) < 30
        )
    }
}
