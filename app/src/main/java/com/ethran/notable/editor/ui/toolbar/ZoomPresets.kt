package com.ethran.notable.editor.ui.toolbar

import kotlin.math.roundToInt

object ZoomPresets {
    val levels = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

    fun label(zoom: Float): String = "${(zoom * 100).roundToInt()}%"
}
