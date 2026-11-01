package com.ethran.notable.data.model
import com.ethran.notable.R

/**
 * Enum class to store the different sheets size.
 */
enum class PageMode(val width: Int, val height: Int, val displayNameId:Int) {
    INFINITE(0, 0, (R.string.page_mode_infinite)),

    // ISO 216
    A3(842, 1191, R.string.page_mode_a3),
    A4(595, 842, R.string.page_mode_a4),
    A5(420, 595, R.string.page_mode_a5),
    // ISO 216
    A6(298, 420, R.string.page_mode_a6),

    // ANSI
    LETTER(612, 792, R.string.page_mode_letter),
    LEGAL(612, 1008, R.string.page_mode_legal),
    TABLOID(792, 1224, R.string.page_mode_tabloid),
}