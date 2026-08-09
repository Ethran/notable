package com.ethran.notable.editor.drawing

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [PageRenderer]'s buffer lifecycle.
 *
 * The resize contract matters because the editor is moving to a **draggable split divider**, which
 * turns re-layout from a rare event (orientation change, toolbar toggle) into a user-driven one.
 * Two properties have to hold or scrolling breaks in ways no unit test elsewhere would catch:
 *
 * 1. Resizing to the current size must not reallocate — a debounced divider will call it
 *    repeatedly with unchanged values.
 * 2. Resizing must drop the spare scroll buffer. It is sized to the *old* viewport, and reusing it
 *    would corrupt the next scroll.
 *
 * Instrumented rather than a unit test: these allocate real android.graphics.Bitmaps, which are
 * no-op stubs on the JVM unit-test classpath (see CLAUDE.md). Unlike the PageView fixtures this
 * needs no Context and no mocks — PageRenderer deliberately depends on neither.
 */
@RunWith(AndroidJUnit4::class)
class PageRendererTest {

    private companion object {
        const val W = 400
        const val H = 300
    }

    private lateinit var renderer: PageRenderer

    @Before
    fun setUp() {
        renderer = PageRenderer(W, H)
    }

    // ---------------------------------------------------------------- construction

    @Test
    fun constructsBufferAtRequestedSize() {
        assertEquals(W, renderer.width)
        assertEquals(H, renderer.height)
        assertEquals(W, renderer.bitmap.width)
        assertEquals(H, renderer.bitmap.height)
    }

    /** Canvas() throws on an immutable bitmap, so the buffer must be mutable. */
    @Test
    fun bufferIsMutable() {
        assertTrue("window buffer must be mutable", renderer.bitmap.isMutable)
    }

    @Test
    fun canvasIsBoundToTheBuffer() {
        assertEquals(renderer.bitmap.width, renderer.canvas.width)
        assertEquals(renderer.bitmap.height, renderer.canvas.height)
    }

    // ---------------------------------------------------------------- resize

    /**
     * The idempotency contract. A debounced divider will call resize with unchanged values, and
     * reallocating ~5 MB per call would thrash the heap and the e-ink panel.
     */
    @Test
    fun resizeToSameDimensionsIsNoOpAndReportsNoChange() {
        val before = renderer.bitmap
        val changed = renderer.resize(W, H)
        assertFalse("resize to identical dimensions should report no change", changed)
        assertSame("resize to identical dimensions must not reallocate", before, renderer.bitmap)
    }

    @Test
    fun resizeToNewDimensionsReallocatesAndReportsChange() {
        val before = renderer.bitmap
        val changed = renderer.resize(W * 2, H + 10)

        assertTrue(changed)
        assertNotSame(before, renderer.bitmap)
        assertEquals(W * 2, renderer.width)
        assertEquals(H + 10, renderer.height)
        assertEquals(W * 2, renderer.bitmap.width)
        assertEquals(H + 10, renderer.bitmap.height)
    }

    /** Narrowing matters as much as widening — a divider drag goes both ways. */
    @Test
    fun resizeSmallerReallocates() {
        renderer.resize(W / 2, H)
        assertEquals(W / 2, renderer.bitmap.width)
        assertEquals(H, renderer.bitmap.height)
    }

    @Test
    fun repeatedResizesLeaveConsistentGeometry() {
        renderer.resize(500, 200)
        renderer.resize(500, 200)
        renderer.resize(120, 640)
        renderer.resize(120, 640)

        assertEquals(120, renderer.width)
        assertEquals(640, renderer.height)
        assertEquals(120, renderer.bitmap.width)
        assertEquals(640, renderer.bitmap.height)
        assertEquals(120, renderer.canvas.width)
        assertEquals(640, renderer.canvas.height)
    }

    /** recreate() always allocates, unlike resize() which short-circuits on equal dimensions. */
    @Test
    fun recreateAlwaysAllocatesAFreshBuffer() {
        val before = renderer.bitmap
        renderer.recreate()
        assertNotSame(before, renderer.bitmap)
        assertEquals(W, renderer.bitmap.width)
        assertEquals(H, renderer.bitmap.height)
    }

    // ---------------------------------------------------------------- scroll buffer

    /**
     * The spare buffer is ping-ponged rather than reallocated: the first shift allocates a spare,
     * the second swaps the original back in. Asserting the *identity* returns to the original
     * proves scrolling does not allocate without bound.
     */
    @Test
    fun shiftPingPongsBetweenTwoBuffers() {
        val original = renderer.bitmap

        renderer.shift(Offset(0f, 10f), 1f)
        val afterFirst = renderer.bitmap
        assertNotSame("first shift should swap in a different buffer", original, afterFirst)

        renderer.shift(Offset(0f, 10f), 1f)
        assertSame("second shift should reuse the original buffer", original, renderer.bitmap)
    }

    /**
     * The property that makes resize safe. After a resize the spare is sized to the old viewport;
     * if it were reused, the next scroll would produce a buffer of the wrong geometry.
     */
    @Test
    fun resizeInvalidatesTheStaleScrollBuffer() {
        renderer.shift(Offset(0f, 10f), 1f)   // populates the spare at the old geometry

        renderer.resize(W * 2, H * 2)
        renderer.shift(Offset(0f, 10f), 1f)   // must not resurrect the old-sized spare

        assertEquals(W * 2, renderer.bitmap.width)
        assertEquals(H * 2, renderer.bitmap.height)
    }

    @Test
    fun shiftReturnsRetainedRegion() {
        val retained = renderer.shift(Offset(0f, 40f), 1f)
        assertEquals(0, retained.left)
        assertEquals(0, retained.top)
        assertEquals(W, retained.right)
        assertEquals(H - 40, retained.bottom)
    }

    /** Scrolling must keep working immediately after a resize, at the new geometry. */
    @Test
    fun shiftAfterResizeUsesNewGeometry() {
        renderer.resize(200, 100)
        val retained = renderer.shift(Offset(0f, 25f), 1f)

        assertEquals(200, retained.right)
        assertEquals(75, retained.bottom)
        assertEquals(200, renderer.bitmap.width)
        assertEquals(100, renderer.bitmap.height)
    }

    // ---------------------------------------------------------------- adopt vs swap

    /**
     * adopt() takes an external bitmap and wraps it in a NEW Canvas — the cached-bitmap path.
     * It also re-derives the dimensions, since a cached buffer may not match the current size.
     */
    @Test
    fun adoptTakesDimensionsFromTheAdoptedBitmapAndRebuildsCanvas() {
        val canvasBefore = renderer.canvas
        val other = PageRenderer(90, 70).bitmap

        renderer.adopt(other)

        assertSame(other, renderer.bitmap)
        assertNotSame("adopt should build a new Canvas", canvasBefore, renderer.canvas)
        assertEquals(90, renderer.width)
        assertEquals(70, renderer.height)
    }

    /**
     * swapBitmap() keeps the SAME Canvas instance, rebinding it via setBitmap — the zoom paths
     * rely on this. Kept distinct from adopt() deliberately; collapsing them would be a behaviour
     * change.
     */
    @Test
    fun swapBitmapReusesTheExistingCanvas() {
        val canvasBefore = renderer.canvas
        val other = PageRenderer(90, 70).bitmap

        renderer.swapBitmap(other)

        assertSame(other, renderer.bitmap)
        assertSame("swapBitmap should reuse the Canvas", canvasBefore, renderer.canvas)
        assertEquals(90, renderer.width)
        assertEquals(70, renderer.height)
    }

    /** A resize after adopting must be measured against the adopted dimensions, not the original. */
    @Test
    fun resizeAfterAdoptComparesAgainstAdoptedDimensions() {
        val other = PageRenderer(90, 70).bitmap
        renderer.adopt(other)

        assertFalse("adopted dimensions should already match", renderer.resize(90, 70))
        assertTrue(renderer.resize(91, 70))
    }
}
