package com.ethran.notable.editor.canvas.rectify

/**
 * A clock the test advances by hand, so dwell and throttle timing is exact and instant. Same shape
 * as the `clockTime` in PointerTrackerTest; pass [invoke] where a `() -> Long` is wanted.
 */
class FakeClock(var now: Long = T0) {
    operator fun invoke(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}
