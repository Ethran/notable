package com.ethran.notable.editor.state

import android.graphics.Color
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.editor.PageView
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import io.shipbook.shipbooksdk.ShipBook

enum class Mode {
    Draw, Erase, Select, Line
}

@Stable
class MenuStates {
    var isStrokeSelectionOpen by mutableStateOf(false)
    var isMenuOpen by mutableStateOf(false)
    var isBackgroundSelectorModalOpen by mutableStateOf(false)
    fun closeAll() {
        isStrokeSelectionOpen = false
        isMenuOpen = false
        isBackgroundSelectorModalOpen = false
    }

    val anyMenuOpen: Boolean
        get() = isStrokeSelectionOpen || isMenuOpen || isBackgroundSelectorModalOpen
}


class EditorState(val bookId: String? = null, pageId: String, val pageView: PageView) {
    var pageId by mutableStateOf(pageId)
        private set

    private val log = ShipBook.getLogger("EditorState")
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
            log.d("Drawing state should be: $shouldBeDrawing (menus open: ${menuStates.anyMenuOpen}, selection active: ${selectionState.isNonEmpty()})")
            isDrawing = shouldBeDrawing
        }
    }

    fun changePage(id: String) {
        log.d("Changing page to $id, from $pageId")
        pageId = id
        closeAllMenus()
        selectionState.reset()
        isDrawing = true
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