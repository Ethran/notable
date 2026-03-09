package com.ethran.notable.editor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting

/**
 * Wrapper around EditorViewModel for backward compatibility with canvas components
 * (DrawCanvas, OnyxInputHandler, CanvasRefreshManager, etc. that still expect EditorState).
 *
 * Holds its own Compose snapshot state (mutableStateOf) so that canvas observers using
 * snapshotFlow remain reactive. Call [syncFrom] whenever [EditorViewModel.toolbarState] changes.
 */
@Stable
class EditorState(
    val viewModel: EditorViewModel,
) {
    private val initial = viewModel.toolbarState.value

    var mode by mutableStateOf(initial.mode)
        private set

    var pen by mutableStateOf(initial.pen)
        private set

    var eraser by mutableStateOf(initial.eraser)
        private set

    var penSettings by mutableStateOf(initial.penSettings)
        private set

    var isToolbarOpen by mutableStateOf(initial.isToolbarOpen)
        private set

    // Intentionally public — canvas components (CanvasObserverRegistry, EditorControlTower,
    // Select) set this directly, and CanvasObserverRegistry uses snapshotFlow { state.isDrawing }.
    var isDrawing by mutableStateOf(initial.isDrawing)

    val selectionState: SelectionState
        get() = viewModel.selectionState

    private var _clipboard by mutableStateOf(Clipboard.content)
    var clipboard: ClipboardContent?
        get() = _clipboard
        set(value) {
            _clipboard = value
            // The clipboard content must survive the EditorState, so we store a copy in
            // a singleton that lives outside of the EditorState
            Clipboard.content = value
            viewModel.setHasClipboard(value != null)
        }

    /**
     * Synchronises this EditorState's mutableStateOf fields from the given [ToolbarUiState].
     * Call this from a LaunchedEffect in EditorView whenever toolbarState changes.
     */
    fun syncFrom(state: ToolbarUiState) {
        mode = state.mode
        pen = state.pen
        eraser = state.eraser
        penSettings = state.penSettings
        isToolbarOpen = state.isToolbarOpen
        isDrawing = state.isDrawing
    }
}
