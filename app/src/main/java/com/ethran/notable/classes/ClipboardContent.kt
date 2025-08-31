package com.ethran.notable.classes

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke

data class ClipboardContent(
    val strokes: List<Stroke> = emptyList(),
    val images: List<Image> = emptyList(),
)