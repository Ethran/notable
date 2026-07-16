package com.ethran.notable.data.datastore

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import kotlinx.serialization.Serializable


// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    /**
     * Updates the globally observed settings. This is a Compose snapshot state read all over the UI
     * during composition, yet [update] is called from background coroutines (e.g. when persisting
     * settings on Dispatchers.IO). Writing the snapshot state directly from a non-composition thread
     * can race the recomposer and throw "Unsupported concurrent change during composition", so the
     * write is committed inside its own global mutable snapshot.
     */
    fun update(settings: AppSettings) {
        Snapshot.withMutableSnapshot {
            _current.value = settings
        }
    }
}

@Serializable
data class AppSettings(
    // General
    val version: Int,
    val monitorBgFiles: Boolean = false,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val scribbleToEraseEnabled: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val continuousZoom: Boolean = false,
    val continuousStrokeSlider: Boolean = false,
    val paginatePdf: Boolean = true,
    val visualizePdfPagination: Boolean = false,
    // null → ToolbarLayout.DEFAULT. Sanitize with ToolbarLayout.validated() when reading:
    // persisted layouts may predate elements or omit the mandatory MENU entry.
    val toolbarLayout: ToolbarLayout? = null,
    // User-created pen instances; layouts reference them as "PEN:<id>". The preset is the
    // single source of truth for a pen's color/size — StrokeMenu edits write back here.
    val toolbarPens: List<ToolbarPen> = ToolbarPen.DEFAULT_PENS,

    // Gestures
    val doubleTapAction: GestureAction = GestureAction.Undo,
    val twoFingerTapAction: GestureAction = GestureAction.ChangeTool,
    val swipeLeftAction: GestureAction = GestureAction.NextPage,
    val swipeRightAction: GestureAction = GestureAction.PreviousPage,
    // Fired by *three*-finger swipes (two fingers are pan/zoom); the field
    // names are kept for persisted-settings compatibility.
    val twoFingerSwipeLeftAction: GestureAction = GestureAction.ToggleZen,
    val twoFingerSwipeRightAction: GestureAction = GestureAction.ToggleZen,
    val holdAction: GestureAction = GestureAction.Select,
    val enableQuickNav: Boolean = true,
    // Onyx only: broadcast onyx.action.INTERCEPT_GESTURE while the app is resumed so
    // SystemUI's three-finger screenshot cannot steal our multi-finger gestures. The
    // same SystemUI pipeline serves the side/bottom edge navigation swipes, so those
    // stop working inside the app while this is on — hence opt-in.
    val blockSystemGestures: Boolean = false,
    val renameOnCreate: Boolean = true,

    // Debug
    val showWelcome: Boolean = true,
    // [system information -- does not have a setting]
    val debugMode: Boolean = false,
    val simpleRendering: Boolean = false,
    val openGLRendering: Boolean = true,
    val muPdfRendering: Boolean = true,
    val destructiveMigrations: Boolean = false,

    ) {
    enum class GestureAction {
        None, Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }
}
