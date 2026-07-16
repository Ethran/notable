package com.ethran.notable.editor.drawing

import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeStyleRegistryTest {

    @Test
    fun `every stroke-producing pen has a style`() {
        for (pen in Pen.entries) {
            if (pen == Pen.DASHED) continue
            assertNotNull(
                "Pen $pen produces persisted strokes and must have a StrokeStyle",
                StrokeStyleRegistry.forPen(pen)
            )
        }
    }

    @Test
    fun `the indicator-only DASHED pen has no style`() {
        assertNull(
            "DASHED is the erase indicator, never persisted ink — no style expected",
            StrokeStyleRegistry.forPen(Pen.DASHED)
        )
    }
}
