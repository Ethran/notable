package com.ethran.notable.editor.state

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clipboard payload shared across the editor.
 */
data class ClipboardContent(
    val strokes: List<Stroke> = emptyList(),
    val images: List<Image> = emptyList(),
)

/**
 * Process-wide clipboard store used by the editor.
 */
object ClipboardStore {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content.asStateFlow()

    fun set(value: ClipboardContent?) {
        _content.value = value
    }

    fun get(): ClipboardContent? = _content.value
}

