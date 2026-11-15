package com.ethran.notable.ui

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.model.SheetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The strips beside a sheet narrower than the view: they are where the pen is blocked, so they must
 * cover exactly the view outside the sheet, inside the view, and nothing when the sheet fills it.
 */
@RunWith(AndroidJUnit4::class)
class SideBarsRectanglesTest {

    private val view = Rect(0, 0, 1404, 1872)

    @Test
    fun aNarrowSheetGetsOneStripEachSide_insideTheView() {
        for (sheetWidth in listOf(600, 601, 1000, 1403)) {
            for (zoom in listOf(0.5f, 0.75f, 1f)) {
                val drawn = sheetWidth * zoom
                if (drawn > view.width() - 0.5f) continue
                val (left, right) = sideBarsRectangles(SheetSize(sheetWidth, 2000), view.width(), view.height(), zoom)
                val message = "sheet $sheetWidth at $zoom"
                assertEquals(message, 0, left.left)
                assertEquals(message, view.width(), right.right)
                assertEquals(message, left.width(), right.width())
                assertTrue(message, left.right <= right.left)
                assertTrue("$message: strips cover the slack", left.width() + right.width() >= view.width() - drawn)
                assertTrue("$message: strips stay off the sheet", left.width() + right.width() <= view.width() - drawn + 2)
            }
        }
    }

    @Test
    fun aSheetFillingOrWiderThanTheView_getsNoStrips() {
        assertEquals(emptyList<Rect>(), sideBarsRectangles(SheetSize(1404, 2000), view.width(), view.height(), 1f))
        assertEquals(emptyList<Rect>(), sideBarsRectangles(SheetSize(2480, 2000), view.width(), view.height(), 1f))
        // Fit to width: the zoom that makes the sheet span the view, up to float rounding.
        assertEquals(
            emptyList<Rect>(),
            sideBarsRectangles(SheetSize(1860, 2632), view.width(), view.height(), view.width() / 1860f),
        )
    }

    @Test
    fun anInfinitePage_getsNoStrips() {
        assertEquals(emptyList<Rect>(), sideBarsRectangles(null, view.width(), view.height(), 0.5f))
    }
}
