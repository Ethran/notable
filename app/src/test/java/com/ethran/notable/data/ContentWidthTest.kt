package com.ethran.notable.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentWidthTest {
    private val screen = 1_000

    @Test fun `empty page is one screen wide`() {
        assertEquals(screen, contentWidth(null, null, screen))
    }

    @Test fun `image beyond the screen widens an image-only page`() {
        assertEquals(2_050, contentWidth(null, 2_000, screen))
    }

    @Test fun `widest of strokes and images wins`() {
        assertEquals(1_550, contentWidth(1_500, 1_100, screen))
    }
}
