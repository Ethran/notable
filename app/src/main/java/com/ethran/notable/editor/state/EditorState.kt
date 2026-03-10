package com.ethran.notable.editor.state

import androidx.compose.runtime.Stable
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.Mode
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting

/**
 * Wrapper around EditorViewModel for backward compatibility with canvas components
 * (DrawCanvas, OnyxInputHandler, CanvasRefreshManager, etc. that still expect EditorState).
 * This is a thin adapter that delegates to the ViewModel.
 */
@Stable
class EditorState(
    val viewModel: EditorViewModel,
) {

    // Delegate to ViewModel
    var mode: Mode
        get() = viewModel.mode
        set(value) {
            viewModel.onToolbarAction(ToolbarAction.ChangeMode(value))
        }

    var pen: Pen
        get() = viewModel.pen
        set(value) {
            viewModel.onToolbarAction(ToolbarAction.ChangePen(value))
        }

    val eraser: Eraser
        get() = viewModel.eraser

    val penSettings: Map<String, PenSetting>
        get() = viewModel.penSettings

    var isToolbarOpen: Boolean
        get() = viewModel.isToolbarOpen
        set(value) {
            if (value != viewModel.isToolbarOpen) {
                viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
            }
        }

    val selectionState: SelectionState
        get() = viewModel.selectionState

    var clipboard: ClipboardContent?
        get() = viewModel.clipboard
        set(value) {
            viewModel.clipboard = value
        }

    var isDrawing: Boolean
        get() = viewModel.isDrawing
        set(value) {
            viewModel.isDrawing = value
        }
}
