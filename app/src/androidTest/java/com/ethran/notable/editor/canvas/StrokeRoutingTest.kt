package com.ethran.notable.editor.canvas

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.Pane
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [routeStrokeToPane], which decides which document a raw stroke is written into.
 *
 * The case that matters is a stroke crossing the divider. On a BOOX Go 10.3 (firmware
 * `2026-05-12_4.2-rel`) the firmware clips the live preview per limit rect but does **not** split
 * the stroke: the drag arrives as one callback whose point list spans both regions, rendering with
 * a visible gap while a single undo removes the whole thing. Hit-testing only the first point would
 * therefore write the far-side points into the near pane at coordinates outside its own rect — ink
 * silently bleeding into the wrong note.
 *
 * A simple point type is used rather than the Onyx `TouchPoint`, which is what the generic
 * signature is for.
 */
@RunWith(AndroidJUnit4::class)
class StrokeRoutingTest {

    private data class P(val x: Float, val y: Float)

    private companion object {
        const val SURFACE_W = 1000
        const val SURFACE_H = 800
        const val GUTTER = 16
    }

    private lateinit var left: Pane
    private lateinit var right: Pane
    private lateinit var panes: List<Pane>

    @Before
    fun setUp() {
        // Pane only touches its screenRect for routing, so the page and history are irrelevant.
        left = Pane(page = mockk(relaxed = true), history = mockk(relaxed = true))
        right = Pane(page = mockk(relaxed = true), history = mockk(relaxed = true))

        val mid = SURFACE_W / 2
        left.layout(Rect(0, 0, mid - GUTTER, SURFACE_H))
        right.layout(Rect(mid + GUTTER, 0, SURFACE_W, SURFACE_H))
        panes = listOf(left, right)
    }

    private fun route(vararg points: P) =
        routeStrokeToPane(points.toList(), panes, { it.x }, { it.y })

    // ---------------------------------------------------------------- the crossing case

    /**
     * The headline behaviour. A drag from the left pane through the gutter into the right must
     * write only to the left document — the one it started in.
     */
    @Test
    fun strokeCrossingTheDividerKeepsOnlyTheStartingPane() {
        val routed = route(
            P(100f, 400f),   // left
            P(400f, 400f),   // left
            P(500f, 400f),   // gutter
            P(700f, 400f),   // right
            P(900f, 400f),   // right
        )!!

        assertSame("must belong to the pane it started in", left, routed.pane)
        assertEquals(2, routed.points.size)
        assertEquals(listOf(100f, 400f), routed.points.map { it.x })
    }

    /** Symmetrical: starting on the right keeps the right-hand run. */
    @Test
    fun strokeCrossingFromTheRightKeepsOnlyTheRightPane() {
        val routed = route(P(900f, 400f), P(700f, 400f), P(100f, 400f))!!

        assertSame(right, routed.pane)
        assertEquals(2, routed.points.size)
        assertEquals(listOf(900f, 700f), routed.points.map { it.x })
    }

    /**
     * A stroke that leaves and returns keeps both of its runs in the owning pane. The points are
     * filtered, not truncated at the first exit — a stroke that dips into the gutter and comes back
     * is one stroke, and dropping its tail would silently lose ink.
     */
    @Test
    fun strokeLeavingAndReturningKeepsBothRuns() {
        val routed = route(P(100f, 400f), P(500f, 400f), P(300f, 400f))!!

        assertSame(left, routed.pane)
        assertEquals(listOf(100f, 300f), routed.points.map { it.x })
    }

    // ---------------------------------------------------------------- ordinary cases

    @Test
    fun strokeWhollyInsideOnePaneIsUnchanged() {
        val routed = route(P(100f, 100f), P(200f, 200f), P(300f, 300f))!!

        assertSame(left, routed.pane)
        assertEquals(3, routed.points.size)
        assertEquals(listOf(100f, 200f, 300f), routed.points.map { it.x })
    }

    @Test
    fun pointOrderIsPreserved() {
        val routed = route(P(300f, 10f), P(100f, 20f), P(200f, 30f))!!
        assertEquals(listOf(300f, 100f, 200f), routed.points.map { it.x })
    }

    /** The first point always survives — it is what selected the pane. */
    @Test
    fun firstPointAlwaysSurvives() {
        val routed = route(P(100f, 400f), P(500f, 400f), P(600f, 400f))!!
        assertEquals(1, routed.points.size)
        assertEquals(100f, routed.points.first().x, 0.001f)
    }

    // ---------------------------------------------------------------- rejected strokes

    /** Starting in the dead gutter belongs to no pane, so the stroke is dropped entirely. */
    @Test
    fun strokeStartingInTheGutterIsRejected() {
        assertNull(route(P(500f, 400f), P(100f, 400f)))
    }

    /** Starting off-surface (chrome, toolbar) is likewise rejected. */
    @Test
    fun strokeStartingOutsideEveryPaneIsRejected() {
        assertNull(route(P(-50f, 400f)))
        assertNull(route(P(400f, 5000f)))
    }

    @Test
    fun emptyStrokeIsRejected() {
        assertNull(route())
    }

    // ---------------------------------------------------------------- degenerate single pane

    /**
     * With one pane covering the surface every stroke belongs to it, which is what keeps
     * single-pane behaviour identical.
     */
    @Test
    fun singleFullSurfacePaneClaimsEverything() {
        val only = Pane(page = mockk(relaxed = true), history = mockk(relaxed = true))
        only.layout(Rect(0, 0, SURFACE_W, SURFACE_H))

        val routed = routeStrokeToPane(
            listOf(P(0f, 0f), P(500f, 400f), P(999f, 799f)),
            listOf(only),
            { it.x },
            { it.y },
        )!!

        assertSame(only, routed.pane)
        assertEquals(3, routed.points.size)
    }
}
