package com.ethran.notable.data.model

/**
 * How a finite sheet is scaled to the viewport.
 *
 */
enum class FitMode {
    /** The sheet at its true physical size (zoom 1); the reader zooms by hand. */
    REAL,

    /** The sheet scaled so its width equals the viewport width; recomputed per viewport. */
    FIT_TO_WIDTH,
}
