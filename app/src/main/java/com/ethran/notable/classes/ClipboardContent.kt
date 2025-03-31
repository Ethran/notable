package com.ethran.notable.classes

data class ClipboardContent(
    val strokes: List<com.ethran.notable.db.Stroke> = emptyList(),
    val images: List<com.ethran.notable.db.Image> = emptyList(),
)