package com.ethran.notable.data.datastore

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
    val neoTools: Boolean = false,
    val scribbleToEraseEnabled: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val continuousZoom: Boolean = false,
    val continuousStrokeSlider: Boolean = false,
    val monochromeMode: Boolean = false,
    val paginatePdf: Boolean = true,
    val visualizePdfPagination: Boolean = false,

    // Gestures
    val doubleTapAction: GestureAction = GestureAction.Undo,
    val twoFingerTapAction: GestureAction = GestureAction.ChangeTool,
    val swipeLeftAction: GestureAction = GestureAction.NextPage,
    val swipeRightAction: GestureAction = GestureAction.PreviousPage,
    val twoFingerSwipeLeftAction: GestureAction = GestureAction.ToggleZen,
    val twoFingerSwipeRightAction: GestureAction = GestureAction.ToggleZen,
    val holdAction: GestureAction = GestureAction.Select,
    val enableQuickNav: Boolean = true,
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
    companion object {
        val defaultDoubleTapAction = GestureAction.Undo
        val defaultTwoFingerTapAction = GestureAction.ChangeTool
        val defaultSwipeLeftAction = GestureAction.NextPage
        val defaultSwipeRightAction = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction = GestureAction.ToggleZen
        val defaultHoldAction = GestureAction.Select
    }

    enum class GestureAction {
        None, Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }
}
