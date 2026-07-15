package com.ethran.notable.gestures

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

private const val T0 = 1_000L

class PointerTrackerTest {

    private var clockTime = T0
    private val tracker = PointerTracker(now = { clockTime })

    // --- Finger counting -------------------------------------------------

    @Test
    fun `staggered downs raise the high-water mark`() {
        tracker.down(1, 0f, 0f, T0)
        assertEquals(1, tracker.maxConcurrentPressed)
        tracker.down(2, 100f, 0f, T0 + 50)
        assertEquals(2, tracker.maxConcurrentPressed)
        assertEquals(2, tracker.pressedCount())
    }

    @Test
    fun `id churn neither inflates nor loses the finger count`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        // The panel drops contact 2 for a few ms and re-reports it as id 3.
        tracker.up(2, 100f, 0f, T0 + 60)
        tracker.down(3, 102f, 1f, T0 + 65)
        assertEquals(2, tracker.maxConcurrentPressed)
        assertEquals(2, tracker.pressedCount())
    }

    @Test
    fun `all fingers lifting keeps the high-water mark for classification`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.up(1, 0f, 0f, T0 + 80)
        tracker.up(2, 100f, 0f, T0 + 85)
        assertEquals(2, tracker.maxConcurrentPressed)
        assertEquals(0, tracker.pressedCount())
    }

    // --- Timing -----------------------------------------------------------

    @Test
    fun `input duration measures first to last input event`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.up(1, 0f, 0f, T0 + 70)
        assertEquals(70L, tracker.inputDuration())
    }

    @Test
    fun `elapsed since start follows the clock even without input`() {
        tracker.down(1, 0f, 0f, T0)
        clockTime = T0 + 400 // stationary finger: no events arrive
        assertEquals(400L, tracker.elapsedSinceStart())
        assertEquals(0L, tracker.inputDuration())
    }

    // --- Pinch ------------------------------------------------------------

    @Test
    fun `pinch pair is fixed at second finger down, even if it moves first`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.moveTo(2, 200f, 0f, T0 + 30)
        assertEquals(1f, tracker.pinchRatio(), 1e-6f)
    }

    @Test
    fun `pinch ratio survives lifted fingers for end-of-gesture zoom`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.moveTo(2, 250f, 0f, T0 + 40)
        tracker.up(2, 250f, 0f, T0 + 60)
        tracker.up(1, 0f, 0f, T0 + 70)
        assertEquals(1.5f, tracker.pinchRatio(), 1e-6f)
    }

    @Test
    fun `a third finger does not disturb the pinch pair`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.down(3, 500f, 500f, T0 + 20)
        tracker.moveTo(3, 900f, 900f, T0 + 40)
        assertEquals(0f, tracker.pinchRatio(), 1e-6f)
        assertEquals(Offset(50f, 0f), tracker.pinchCenter())
    }

    @Test
    fun `consume pinch delta is incremental`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        assertEquals(0f, tracker.consumePinchDelta(), 1e-6f) // establishes reference
        tracker.moveTo(2, 150f, 0f, T0 + 30)
        assertEquals(0.5f, tracker.consumePinchDelta(), 1e-6f)
        tracker.moveTo(2, 300f, 0f, T0 + 50)
        assertEquals(1f, tracker.consumePinchDelta(), 1e-6f) // 300/150 - 1
    }

    // --- Drag deltas ------------------------------------------------------

    @Test
    fun `consume drag delta returns centroid movement since previous call`() {
        tracker.down(1, 0f, 0f, T0)
        assertEquals(Offset.Zero, tracker.consumeDragDelta()) // establishes reference
        tracker.moveTo(1, 10f, 20f, T0 + 20)
        assertEquals(Offset(10f, 20f), tracker.consumeDragDelta())
    }

    @Test
    fun `drag delta reference resets when a finger lands`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.consumeDragDelta()
        tracker.moveTo(1, 50f, 0f, T0 + 20)
        tracker.down(2, 300f, 0f, T0 + 30) // centroid jumps to (175, 0)
        assertEquals(Offset.Zero, tracker.consumeDragDelta()) // no bogus jump
        tracker.moveTo(1, 60f, 0f, T0 + 40)
        tracker.moveTo(2, 310f, 0f, T0 + 40)
        assertEquals(Offset(10f, 0f), tracker.consumeDragDelta())
    }

    @Test
    fun `drag delta reference resets on id churn`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 100f, 0f, T0 + 10)
        tracker.consumeDragDelta()
        tracker.up(2, 100f, 0f, T0 + 30)
        tracker.down(3, 102f, 0f, T0 + 35)
        assertEquals(Offset.Zero, tracker.consumeDragDelta())
    }

    @Test
    fun `centroid travel measures translation of the finger pair`() {
        tracker.down(1, 100f, 0f, T0)
        tracker.down(2, 200f, 0f, T0 + 10)
        // Both fingers slide right by 40: the centroid travels 40.
        tracker.moveTo(1, 140f, 0f, T0 + 30)
        tracker.moveTo(2, 240f, 0f, T0 + 30)
        assertEquals(40f, tracker.centroidTravel(), 1e-4f)
    }

    @Test
    fun `a symmetric pinch leaves the centroid still`() {
        tracker.down(1, 100f, 0f, T0)
        tracker.down(2, 200f, 0f, T0 + 10)
        // Spread symmetrically about the down-centroid (150): -40 and +40 cancel.
        tracker.moveTo(1, 60f, 0f, T0 + 30)
        tracker.moveTo(2, 240f, 0f, T0 + 30)
        assertEquals(0f, tracker.centroidTravel(), 1e-4f)
    }

    // --- Directional drags ------------------------------------------------

    @Test
    fun `horizontal drag is the smallest horizontal movement across fingers`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 0f, 100f, T0 + 10)
        tracker.moveTo(1, 300f, 10f, T0 + 40)
        tracker.moveTo(2, 250f, 90f, T0 + 40)
        assertEquals(250f, tracker.horizontalDrag(), 1e-6f)
    }

    @Test
    fun `mostly-vertical movement yields no horizontal drag`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.moveTo(1, 50f, 300f, T0 + 40)
        assertEquals(0f, tracker.horizontalDrag(), 1e-6f)
        assertEquals(300f, tracker.verticalDrag(), 1e-6f)
    }

    @Test
    fun `a phantom low-delta contact does not veto a swipe`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.moveTo(1, 300f, 5f, T0 + 100)
        // Stray palm contact with a tiny, slightly-vertical delta.
        tracker.down(9, 500f, 500f, T0 + 40)
        tracker.moveTo(9, 501f, 503f, T0 + 60)
        tracker.up(9, 501f, 503f, T0 + 70)
        assertEquals(300f, tracker.horizontalDrag(noiseFloorPx = 10f), 1e-6f)
    }

    // --- Net centroid travel (multi-finger swipe distance) -----------------

    @Test
    fun `net centroid travel survives a finger re-landing mid-swipe`() {
        tracker.down(1, 0f, 0f, T0)
        tracker.down(2, 0f, 100f, T0 + 10)
        tracker.moveTo(1, 100f, 0f, T0 + 40)
        tracker.moveTo(2, 100f, 100f, T0 + 40)
        // The panel drops contact 2 and re-reports it as id 3; the swipe goes on.
        tracker.up(2, 100f, 100f, T0 + 60)
        tracker.down(3, 102f, 100f, T0 + 65)
        tracker.moveTo(1, 300f, 0f, T0 + 100)
        tracker.moveTo(3, 302f, 100f, T0 + 100)
        assertEquals(300f, tracker.netCentroidTravel().x, 1e-4f)
    }

    @Test
    fun `a symmetric pinch accumulates no net centroid travel`() {
        tracker.down(1, 100f, 0f, T0)
        tracker.down(2, 200f, 0f, T0 + 10)
        tracker.moveTo(1, 60f, 0f, T0 + 30)
        tracker.moveTo(2, 240f, 0f, T0 + 30)
        assertEquals(0f, tracker.netCentroidTravel().x, 1e-4f)
    }
}
