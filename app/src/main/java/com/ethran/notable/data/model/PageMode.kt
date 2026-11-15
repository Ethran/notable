package com.ethran.notable.data.model

import androidx.annotation.StringRes
import com.ethran.notable.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sheet presets a notebook can be created with, by paper size in PDF points. A preset only
 * gives the sheet's proportions: [sheetSize] turns it into a [SheetSize] in page pixels, sized to
 * the screen it is created on. The ISO A sizes share one proportion, so A4 stands for all of them.
 */
enum class PageMode(val width: Int, val height: Int, @StringRes val displayNameId: Int) {
    INFINITE(0, 0, R.string.page_mode_infinite),
    A4(595, 842, R.string.page_mode_a4),
    LETTER(612, 792, R.string.page_mode_letter),
    LEGAL(612, 1008, R.string.page_mode_legal),
    TABLOID(792, 1224, R.string.page_mode_tabloid);

    /**
     * The sheet for this preset in [orientation], as wide as the screen held that way: its short
     * side in portrait, its long side in landscape. Null for [INFINITE].
     */
    fun sheetSize(orientation: SheetOrientation, screenWidth: Int, screenHeight: Int): SheetSize? {
        if (this == INFINITE) return null
        return when (orientation) {
            SheetOrientation.PORTRAIT -> {
                val sheetWidth = minOf(screenWidth, screenHeight)
                SheetSize(sheetWidth, (sheetWidth.toFloat() * height / width).roundToInt())
            }

            SheetOrientation.LANDSCAPE -> {
                val sheetWidth = maxOf(screenWidth, screenHeight)
                SheetSize(sheetWidth, (sheetWidth.toFloat() * width / height).roundToInt())
            }
        }
    }

    companion object {
        /** The preset with [sheet]'s proportions, in either orientation; null if none matches. */
        fun matching(sheet: SheetSize): PageMode? {
            val long = maxOf(sheet.width, sheet.height).toFloat()
            val short = minOf(sheet.width, sheet.height).toFloat()
            return entries.firstOrNull {
                it != INFINITE && abs(long / short - it.height.toFloat() / it.width) < 0.01f
            }
        }
    }
}

enum class SheetOrientation { PORTRAIT, LANDSCAPE }
