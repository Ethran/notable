package com.ethran.notable.data.model

/** How a page with a sheet is scaled to the view. */
enum class FitMode {
    /** The sheet at its stored size (zoom 1), as on the device that created it. */
    REAL,

    /** The sheet scaled so its width equals the view's width; recomputed for every view size. */
    FIT_TO_WIDTH,
}
