package com.ethran.notable.editor.canvas.rectify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RectBounds] stands in for `android.graphics.Rect`, whose stubs in the JVM unit-test source set
 * discard everything — even `Rect(10, 20, 30, 40).left` comes back 0 — so every band-bound
 * assertion in this suite depends on this type doing the arithmetic for real.
 */
class RectBoundsTest {

    @Test
    fun `constructor keeps its edges`() {
        val r = RectBounds(10, 20, 30, 40)
        assertEquals(10, r.left)
        assertEquals(20, r.top)
        assertEquals(30, r.right)
        assertEquals(40, r.bottom)
        assertEquals(20, r.width)
        assertEquals(20, r.height)
    }

    @Test
    fun `union covers both`() {
        assertEquals(
            RectBounds(10, 20, 70, 80),
            RectBounds(10, 20, 30, 40).union(RectBounds(50, 60, 70, 80)),
        )
    }

    @Test
    fun `union of a rect with itself is unchanged`() {
        val r = RectBounds(5, 6, 7, 8)
        assertEquals(r, r.union(r))
    }

    @Test
    fun `union widens the extremes of both`() {
        assertEquals(
            RectBounds(0, 0, 30, 40),
            RectBounds(10, 20, 30, 40).union(RectBounds(0, 0, 5, 5)),
        )
    }

    @Test
    fun `a first band unioned into the empty rect spans its own area plus the origin`() {
        // bandBounds starts from EMPTY rather than skipping the union, exactly as production starts
        // from `Rect()`. That drags the origin into the rect, which is why the clip to the screen
        // below matters: the band is only ever pushed after sorting and clipping.
        assertEquals(
            RectBounds(0, 0, 309, 309),
            RectBounds.EMPTY.union(RectBounds(91, 91, 309, 309)),
        )
    }

    @Test
    fun `a later band unions onto the area already pushed`() {
        assertEquals(
            RectBounds(91, 91, 909, 709),
            RectBounds(91, 91, 309, 309).union(RectBounds(891, 691, 909, 709)),
        )
    }

    @Test
    fun `inflate grows every edge`() {
        assertEquals(RectBounds(6, 6, 34, 34), RectBounds(10, 10, 30, 30).inflate(4))
    }

    @Test
    fun `sort orders inverted edges`() {
        assertEquals(RectBounds(1, 2, 5, 6), RectBounds(5, 6, 1, 2).sorted())
    }

    @Test
    fun `isEmpty means no area`() {
        // What bandBounds and the adapter's push check actually rely on: a rect with no width or no
        // height is empty, and EMPTY — the value bandBounds returns when the clip misses — is empty.
        assertTrue(RectBounds.EMPTY.isEmpty)
        assertTrue(RectBounds(0, 10, 0, 20).isEmpty) // no width
        assertTrue(RectBounds(10, 0, 20, 0).isEmpty) // no height
        // Sorting turns an inverted rect into a real area, and one pixel across and down is an
        // area too. Only a zero width or a zero height is empty, which is what bandBounds needs
        // from the clip: a band that misses the screen entirely yields EMPTY, never a thin slice.
        assertEquals(RectBounds(1, 1, 5, 5), RectBounds(5, 5, 1, 1).sorted())
        assertFalse(RectBounds(5, 5, 1, 1).sorted().isEmpty)
        assertFalse(RectBounds(0, 0, 1, 1).isEmpty)

        // Same x on both vertical edges: no width, so empty even though top < bottom.
        assertTrue(RectBounds(5, 1, 5, 6).isEmpty)
    }

    @Test
    fun `intersect returns the overlap`() {
        assertEquals(
            RectBounds(20, 30, 40, 50),
            RectBounds(0, 0, 100, 100).intersect(RectBounds(20, 30, 40, 50)),
        )
    }

    @Test
    fun `intersect of disjoint rects is null`() {
        assertNull(RectBounds(0, 0, 10, 10).intersect(RectBounds(20, 20, 30, 30)))
    }

    @Test
    fun `intersect of edge-touching rects is null, as an empty area is nothing to push`() {
        assertNull(RectBounds(0, 0, 10, 10).intersect(RectBounds(10, 0, 20, 10)))
    }

    @Test
    fun `intersect narrows to the overlap, and reports nothing when the rects miss`() {
        assertNull(RectBounds(0, 0, 10, 10).intersect(RectBounds(20, 20, 30, 30)))
        assertEquals(
            RectBounds(20, 30, 40, 50),
            RectBounds(0, 0, 100, 100).intersect(RectBounds(20, 30, 40, 50)),
        )
    }

    @Test
    fun `a band fully off-screen clips to nothing`() {
        // The case that must never be pushed to the panel: a band beyond the screen's right edge.
        val band = RectBounds(1100, 100, 1200, 200)
        assertNull(band.sorted().intersect(RectBounds.ofSize(SCREEN_W, SCREEN_H)))
    }

    @Test
    fun `ofSize spans the whole screen`() {
        assertEquals(RectBounds(0, 0, 1000, 800), RectBounds.ofSize(1000, 800))
    }
}
