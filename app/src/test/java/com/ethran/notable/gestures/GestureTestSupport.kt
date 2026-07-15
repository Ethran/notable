package com.ethran.notable.gestures

import androidx.compose.ui.geometry.Offset

// Readable wrappers around the raw-value update overload for synthetic
// gesture sequences. Timestamps use the same arbitrary base as the test
// clock (see T0 in the test classes).

internal fun PointerTracker.down(id: Long, x: Float, y: Float, at: Long) =
    update(id, Offset(x, y), pressed = true, timestamp = at)

internal fun PointerTracker.moveTo(id: Long, x: Float, y: Float, at: Long) =
    update(id, Offset(x, y), pressed = true, timestamp = at)

internal fun PointerTracker.up(id: Long, x: Float, y: Float, at: Long) =
    update(id, Offset(x, y), pressed = false, timestamp = at)
