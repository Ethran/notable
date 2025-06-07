package com.ethran.notable.utils

import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.TAG
import com.ethran.notable.classes.ClipboardContent
import com.ethran.notable.classes.PageView
import com.ethran.notable.classes.SelectionState
import com.ethran.notable.datastore.EditorSettingCacheManager

enum class Mode {
    Draw, Erase, Select, Line
}

@Stable
class MenuStates {
    var isStrokeSelectionOpen by mutableStateOf(false)
    var isMenuOpen by mutableStateOf(false)
    var isPageSettingsModalOpen by mutableStateOf(false)
    fun closeAll() {
        isStrokeSelectionOpen = false
        isMenuOpen = false
        isPageSettingsModalOpen = false
    }

    val anyMenuOpen: Boolean
        get() = isStrokeSelectionOpen || isMenuOpen || isPageSettingsModalOpen
}


class EditorState(val bookId: String? = null, val pageId: String, val pageView: PageView) {

    private val persistedEditorSettings = EditorSettingCacheManager.getEditorSettings()

    var mode by mutableStateOf(persistedEditorSettings?.mode ?: Mode.Draw) // should save
    var pen by mutableStateOf(persistedEditorSettings?.pen ?: Pen.BALLPEN) // should save
    var eraser by mutableStateOf(persistedEditorSettings?.eraser ?: Eraser.PEN) // should save
    var isDrawing by mutableStateOf(true)
    // For debugging:
//    var isDrawing: Boolean
//        get() = _isDrawing
//        set(value) {
//            if (_isDrawing != value) {
//                Log.d(TAG, "isDrawing modified from ${_isDrawing} to $value")
//                logCallStack("isDrawing modification")
//                _isDrawing = value
//            }
//        }
//
//    private fun logCallStack(reason: String) {
//        val stackTrace = Thread.currentThread().stackTrace
//            .drop(3) // Skip internal calls
//            .take(8) // Limit depth
//            .joinToString("\n") {
//                "${it.className.removePrefix("com.ethran.notable.")}.${it.methodName} (${it.fileName}:${it.lineNumber})"
//            }
//        Log.d(TAG, "$reason call stack:\n$stackTrace")
//    }

    var isToolbarOpen by mutableStateOf(
        persistedEditorSettings?.isToolbarOpen ?: false
    ) // should save
    var penSettings by mutableStateOf(
        persistedEditorSettings?.penSettings ?: mapOf(
            Pen.BALLPEN.penName to PenSetting(5f, Color.BLACK),
            Pen.REDBALLPEN.penName to PenSetting(5f, Color.RED),
            Pen.BLUEBALLPEN.penName to PenSetting(5f, Color.BLUE),
            Pen.GREENBALLPEN.penName to PenSetting(5f, Color.GREEN),
            Pen.PENCIL.penName to PenSetting(5f, Color.BLACK),
            Pen.BRUSH.penName to PenSetting(5f, Color.BLACK),
            Pen.MARKER.penName to PenSetting(40f, Color.LTGRAY),
            Pen.FOUNTAIN.penName to PenSetting(5f, Color.BLACK)
        )
    )

    val selectionState = SelectionState()

    private var _clipboard by mutableStateOf(Clipboard.content)
    var clipboard
        get() = _clipboard
        set(value) {
            this._clipboard = value

            // The clipboard content must survive the EditorState, so we store a copy in
            // a singleton that lives outside of the EditorState
            Clipboard.content = value
        }

    val menuStates = MenuStates()
    fun closeAllMenus() = menuStates.closeAll()

    fun checkForSelectionsAndMenus() {
        val shouldBeDrawing = !menuStates.anyMenuOpen && !selectionState.isNonEmpty()
        if (isDrawing != shouldBeDrawing) {
            Log.d(
                TAG, "We shouldn't be drawing: $shouldBeDrawing, " +
                        "menus: ${menuStates.anyMenuOpen}, selection: ${selectionState.isNonEmpty()}"
            )
            isDrawing = shouldBeDrawing
        }
    }
}

// if state is Move then applySelectionDisplace() will delete original strokes and images
enum class PlacementMode {
    Move,
    Paste
}

object Clipboard {
    var content: ClipboardContent? = null
}