package com.ethran.notable.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class FitZoomTest {
    @Test fun `fit page uses the limiting dimension`() {
        assertEquals(0.5f, fitZoom(1_000, 500, 1_000, 1_000))
    }

    @Test fun `fit stays inside the zoom bounds`() {
        assertEquals(10f, fitZoom(1_000, 1_000, 0, 0))
        assertEquals(0.1f, fitZoom(100, 100, 10_000, 10_000))
    }
}
