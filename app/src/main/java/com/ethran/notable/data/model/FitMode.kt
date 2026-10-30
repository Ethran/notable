package com.ethran.notable.data.model

/**
 * How a finite sheet is scaled to the viewport.
 *
 * Device-independent by design: this is a stored **intent**, never a zoom value. Each view derives
 * its own `zoom = viewWidth / sheetWidth` from its viewport, so one stored preference stays correct
 * across rotation, window resizes and different screens — a fitted sheet on a phone is a fitted
 * sheet, at a different zoom, on a tablet.
 *
 * Stored per orientation on [com.ethran.notable.data.db.Notebook] (`fitPortrait` / `fitLandscape`)
 * and synced with the notebook. Room converts the enum to TEXT by name, like `PageMode`; no
 * [androidx.room.TypeConverter] is needed.
 */
enum class FitMode {
    /** The sheet at its true physical size (zoom 1); the reader zooms by hand. */
    REAL,

    /** The sheet scaled so its width equals the viewport width; recomputed per viewport. */
    FIT_TO_WIDTH,
}
