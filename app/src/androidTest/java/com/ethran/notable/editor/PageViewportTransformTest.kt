package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screen reused for another viewport has to show every page point where that viewport shows it,
 * whatever the two viewports are: negative scroll (a centred sheet), zooming in or out, both axes.
 */
@RunWith(AndroidJUnit4::class)
class PageViewportTransformTest {

    private val viewportPairs = listOf(
        PageViewport() to PageViewport(Offset(-120f, 40f), 0.75f),
        PageViewport(Offset(-428.7f, 191.1f), 0.2605f) to PageViewport(Offset(-428.8f, 188.3f), 0.2607f),
        PageViewport(Offset(300f, 1200f), 2f) to PageViewport(Offset(0f, 900f), 0.5f),
        PageViewport(Offset(0f, 900f), 0.5f) to PageViewport(Offset(300f, 1200f), 2f),
    )

    private val pagePoints = listOf(Offset.Zero, Offset(250f, -80f), Offset(1640f, 3000f))

    @Test
    fun screenTransformTo_drawsEveryPagePointWhereTheTargetViewportShowsIt() {
        for ((from, to) in viewportPairs) {
            val transform = from.screenTransformTo(to)
            for (point in pagePoints) {
                val onScreen = floatArrayOf(
                    (point.x - from.scroll.x) * from.zoom,
                    (point.y - from.scroll.y) * from.zoom,
                )
                transform.mapPoints(onScreen)
                val message = "page point $point, $from -> $to"
                assertEquals(message, (point.x - to.scroll.x) * to.zoom, onScreen[0], 0.01f)
                assertEquals(message, (point.y - to.scroll.y) * to.zoom, onScreen[1], 0.01f)
            }
        }
    }
}
