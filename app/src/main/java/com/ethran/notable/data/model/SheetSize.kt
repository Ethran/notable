package com.ethran.notable.data.model

import androidx.room.ColumnInfo

/**
 * The size of a page's sheet, in page pixels: the units strokes and images are stored in. It is
 * fixed when the page is created, so the sheet is the same on every device. A page without one is
 * an infinite canvas.
 *
 * Embedded with a prefix, so its columns are `<prefix>Width` and `<prefix>Height`.
 */
data class SheetSize(
    @ColumnInfo(name = "Width") val width: Int,
    @ColumnInfo(name = "Height") val height: Int,
)
