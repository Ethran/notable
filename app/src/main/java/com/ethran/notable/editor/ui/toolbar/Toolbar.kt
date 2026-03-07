package com.ethran.notable.editor.ui.toolbar


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("Toolbar")

/**
 * UI State for the Toolbar to make it previewable and decoupled from logic.
 */
data class ToolbarUiState(
    val isToolbarOpen: Boolean = true,
    val mode: Mode = Mode.Draw,
    val pen: Pen = Pen.BALLPEN,
    val penSettings: Map<String, PenSetting> = emptyMap(),
    val eraser: Eraser = Eraser.PEN,
    val isMenuOpen: Boolean = false,
    val isStrokeSelectionOpen: Boolean = false,
    val isBackgroundSelectorModalOpen: Boolean = false,
    val pageNumberInfo: String = "1/1",
    val hasClipboard: Boolean = false,
    val showResetView: Boolean = false,
    // Background Selector specific data
    val backgroundType: String = "native",
    val backgroundPath: String = "blank",
    val backgroundPageNumber: Int = 0,
    val notebookId: String? = null,
    val currentPageNumber: Int = 0
)

private fun isSelected(state: ToolbarUiState, penType: Pen): Boolean {
    return when (state.mode) {
        Mode.Draw if state.pen == penType -> {
            true
        }

        Mode.Line if state.pen == penType -> {
            true
        }

        else -> {
            false
        }
    }
}


private val SIZES_STROKES_DEFAULT = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)
private val SIZES_MARKER_DEFAULT = listOf("M" to 25f, "L" to 40f, "XL" to 60f, "XXL" to 80f)


@Composable
fun Toolbar(
    state: EditorState,
    controlTower: EditorControlTower,
    pageNumberInfo: String,
    onNavigateToLibrary: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    onNavigateToPages: () -> Unit,
    onNavigateToHome: () -> Unit,
    onToggleScribbleToErase: (Boolean) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onExport: suspend (ExportTarget, ExportFormat) -> String
) {
    val scope = rememberCoroutineScope()

    // Observe zoom level to decide button visibility
    val zoomLevel by state.pageView.zoomLevel.collectAsState()
    val showResetView = state.pageView.scroll != androidx.compose.ui.geometry.Offset.Zero || zoomLevel != 1.0f

    val uiState = ToolbarUiState(
        isToolbarOpen = state.isToolbarOpen,
        mode = state.mode,
        pen = state.pen,
        penSettings = state.penSettings,
        eraser = state.eraser,
        isMenuOpen = state.menuStates.isMenuOpen,
        isStrokeSelectionOpen = state.menuStates.isStrokeSelectionOpen,
        isBackgroundSelectorModalOpen = state.menuStates.isBackgroundSelectorModalOpen,
        pageNumberInfo = pageNumberInfo,
        hasClipboard = state.clipboard != null,
        showResetView = showResetView,
        backgroundType = state.pageView.pageFromDb?.backgroundType ?: "native",
        backgroundPath = state.pageView.pageFromDb?.background ?: "blank",
        backgroundPageNumber = state.pageView.getBackgroundPageNumber(),
        notebookId = state.pageView.pageFromDb?.notebookId,
        currentPageNumber = state.pageView.currentPageNumber
    )

    ToolbarContent(
        uiState = uiState,
        onToggleToolbar = { state.isToolbarOpen = !state.isToolbarOpen },
        onModeChange = { state.mode = it },
        onPenChange = { pen ->
            if (state.mode == Mode.Draw && state.pen == pen) {
                state.menuStates.isStrokeSelectionOpen = true
            } else {
                state.mode = Mode.Draw
                state.pen = pen
            }
        },
        onPenSettingChange = { pen, setting ->
            val settings = state.penSettings.toMutableMap()
            settings[pen.penName] = setting
            state.penSettings = settings
        },
        onEraserChange = { state.eraser = it },
        onMenuToggle = { state.menuStates.isMenuOpen = !state.menuStates.isMenuOpen },
        onBackgroundSelectorToggle = { state.menuStates.isBackgroundSelectorModalOpen = it },
        onUndo = { controlTower.undo() },
        onRedo = { controlTower.redo() },
        onPaste = { controlTower.pasteFromClipboard() },
        onResetView = { controlTower.resetZoomAndScroll() },
        onNavigateToLibrary = onNavigateToLibrary,
        onNavigateToBugReport = onNavigateToBugReport,
        onNavigateToPages = onNavigateToPages,
        onNavigateToHome = onNavigateToHome,
        onToggleScribbleToErase = onToggleScribbleToErase,
        onImagePicked = onImagePicked,
        onExport = onExport,
        onBackgroundChange = { type, path ->
            val updatedPage = if (path == null)
                state.pageView.pageFromDb!!.copy(backgroundType = type)
            else state.pageView.pageFromDb!!.copy(background = path, backgroundType = type)
            state.pageView.updatePageSettings(updatedPage)
            scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
        },
        onDrawingStateCheck = { state.checkForSelectionsAndMenus() }
    )
}

@Composable
fun ToolbarContent(
    uiState: ToolbarUiState,
    onToggleToolbar: () -> Unit,
    onModeChange: (Mode) -> Unit,
    onPenChange: (Pen) -> Unit,
    onPenSettingChange: (Pen, PenSetting) -> Unit,
    onEraserChange: (Eraser) -> Unit,
    onMenuToggle: () -> Unit,
    onBackgroundSelectorToggle: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPaste: () -> Unit,
    onResetView: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    onNavigateToPages: () -> Unit,
    onNavigateToHome: () -> Unit,
    onToggleScribbleToErase: (Boolean) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onExport: suspend (ExportTarget, ExportFormat) -> String,
    onBackgroundChange: (String, String?) -> Unit,
    onDrawingStateCheck: () -> Unit
) {

    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            if (uri == null) {
                log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
                return@rememberLauncherForActivityResult
            }
            onImagePicked(uri)
        }

    // on exit of toolbar, update drawing state
    LaunchedEffect(uiState.isBackgroundSelectorModalOpen, uiState.isMenuOpen) {
        log.i("Updating drawing state")
        onDrawingStateCheck()
    }

    if (uiState.isBackgroundSelectorModalOpen) {
        BackgroundSelector(
            initialPageBackgroundType = uiState.backgroundType,
            initialPageBackground = uiState.backgroundPath,
            initialPageNumberInPdf = uiState.backgroundPageNumber,
            notebookId = uiState.notebookId,
            pageNumberInBook = uiState.currentPageNumber,
            onChange = onBackgroundChange,
            onClose = { onBackgroundSelectorToggle(false) }
        )
    }

    if (uiState.isToolbarOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((BUTTON_SIZE + 2).dp)
                .padding(bottom = 1.dp)
        ) {
            if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Black)
                )
            }
            Row(
                Modifier
                    .background(Color.White)
                    .height(BUTTON_SIZE.dp)
                    .fillMaxWidth()
            ) {
                ToolbarButton(
                    onSelect = onToggleToolbar, vectorIcon = FeatherIcons.EyeOff, contentDescription = "close toolbar"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    pen = Pen.BALLPEN,
                    icon = R.drawable.ballpen,
                    isSelected = isSelected(uiState, Pen.BALLPEN),
                    onSelect = { onPenChange(Pen.BALLPEN) },
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = uiState.penSettings[Pen.BALLPEN.penName] ?: PenSetting(5f, android.graphics.Color.BLACK),
                    onChangeSetting = { onPenSettingChange(Pen.BALLPEN, it) })

                if (!GlobalAppSettings.current.monochromeMode) {
                    PenToolbarButton(
                        pen = Pen.REDBALLPEN,
                        icon = R.drawable.ballpenred,
                        isSelected = isSelected(uiState, Pen.REDBALLPEN),
                        onSelect = { onPenChange(Pen.REDBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.REDBALLPEN.penName] ?: PenSetting(5f, android.graphics.Color.RED),
                        onChangeSetting = { onPenSettingChange(Pen.REDBALLPEN, it) },
                    )

                    PenToolbarButton(
                        pen = Pen.BLUEBALLPEN,
                        icon = R.drawable.ballpenblue,
                        isSelected = isSelected(uiState, Pen.BLUEBALLPEN),
                        onSelect = { onPenChange(Pen.BLUEBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.BLUEBALLPEN.penName] ?: PenSetting(5f, android.graphics.Color.BLUE),
                        onChangeSetting = { onPenSettingChange(Pen.BLUEBALLPEN, it) },
                    )
                    PenToolbarButton(
                        pen = Pen.GREENBALLPEN,
                        icon = R.drawable.ballpengreen,
                        isSelected = isSelected(uiState, Pen.GREENBALLPEN),
                        onSelect = { onPenChange(Pen.GREENBALLPEN) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.GREENBALLPEN.penName] ?: PenSetting(5f, android.graphics.Color.GREEN),
                        onChangeSetting = { onPenSettingChange(Pen.GREENBALLPEN, it) },
                    )
                }
                if (GlobalAppSettings.current.neoTools) {
                    PenToolbarButton(
                        pen = Pen.PENCIL,
                        icon = R.drawable.pencil,
                        isSelected = isSelected(uiState, Pen.PENCIL),
                        onSelect = { onPenChange(Pen.PENCIL) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.PENCIL.penName] ?: PenSetting(5f, android.graphics.Color.BLACK),
                        onChangeSetting = { onPenSettingChange(Pen.PENCIL, it) },
                    )

                    PenToolbarButton(
                        pen = Pen.BRUSH,
                        icon = R.drawable.brush,
                        isSelected = isSelected(uiState, Pen.BRUSH),
                        onSelect = { onPenChange(Pen.BRUSH) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.BRUSH.penName] ?: PenSetting(5f, android.graphics.Color.BLACK),
                        onChangeSetting = { onPenSettingChange(Pen.BRUSH, it) },
                    )
                }
                PenToolbarButton(
                    pen = Pen.FOUNTAIN,
                    icon = R.drawable.fountain,
                    isSelected = isSelected(uiState, Pen.FOUNTAIN),
                    onSelect = { onPenChange(Pen.FOUNTAIN) },
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = uiState.penSettings[Pen.FOUNTAIN.penName] ?: PenSetting(5f, android.graphics.Color.BLACK),
                    onChangeSetting = { onPenSettingChange(Pen.FOUNTAIN, it) },
                )

                LineToolbarButton(
                    unSelect = { onModeChange(Mode.Draw) },
                    icon = R.drawable.line,
                    isSelected = uiState.mode == Mode.Line,
                    onSelect = { onModeChange(Mode.Line) },
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    pen = Pen.MARKER,
                    icon = R.drawable.marker,
                    isSelected = isSelected(uiState, Pen.MARKER),
                    onSelect = { onPenChange(Pen.MARKER) },
                    sizes = SIZES_MARKER_DEFAULT,
                    penSetting = uiState.penSettings[Pen.MARKER.penName] ?: PenSetting(40f, android.graphics.Color.LTGRAY),
                    onChangeSetting = { onPenSettingChange(Pen.MARKER, it) })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                EraserToolbarButton(
                    isSelected = uiState.mode == Mode.Erase,
                    onSelect = { onModeChange(Mode.Erase) },
                    value = uiState.eraser,
                    onChange = onEraserChange,
                    toggleScribbleToErase = onToggleScribbleToErase,
                    onMenuOpenChange = {}
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                ToolbarButton(
                    isSelected = uiState.mode == Mode.Select,
                    onSelect = { onModeChange(Mode.Select) },
                    iconId = R.drawable.lasso,
                    contentDescription = "lasso"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    iconId = R.drawable.image,
                    contentDescription = "Image picker",
                    onSelect = {
                        // Call insertImage when the button is tapped
                        log.i("Launching image picker...")
                        pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (uiState.hasClipboard) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        contentDescription = "paste",
                        onSelect = onPaste
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                if (uiState.showResetView) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.RefreshCcw,
                        contentDescription = "reset zoom and scroll",
                        onSelect = onResetView
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    onSelect = onUndo,
                    iconId = R.drawable.undo,
                    contentDescription = "undo"
                )

                ToolbarButton(
                    onSelect = onRedo,
                    iconId = R.drawable.redo,
                    contentDescription = "redo"
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                if (uiState.notebookId != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(35.dp)
                            .padding(10.dp, 0.dp)
                    ) {
                        Text(
                            text = uiState.pageNumberInfo,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.noRippleClickable(onNavigateToPages),
                            textAlign = TextAlign.Center
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }
                // Add Library Button
                ToolbarButton(
                    iconId = R.drawable.home,
                    contentDescription = "library",
                    onSelect = onNavigateToHome
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                Column {
                    ToolbarButton(
                        onSelect = onMenuToggle, iconId = R.drawable.menu, contentDescription = "menu"
                    )
                    if (uiState.isMenuOpen)
                        ToolbarMenu(
                            onExport = onExport,
                            goToBugReport = onNavigateToBugReport,
                            goToLibrary = onNavigateToLibrary,
                            currentPageId = uiState.currentPageId,
                            currentBookId = uiState.notebookId,
                            onClose = {
                                if (uiState.isMenuOpen) {
                                    onMenuToggle()
                                }
                            },
                            onBackgroundSelectorModalOpen = {
                                onBackgroundSelectorToggle(true)
                            }
                        )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black)
            )
        }
    } else {
        // Button to show Toolbar
        ToolbarButton(
            onSelect = onToggleToolbar,
            iconId = presentlyUsedToolIcon(uiState.mode, uiState.pen),
            penColor = if (uiState.mode != Mode.Erase) uiState.penSettings[uiState.pen.penName]?.color?.let {
                Color(it)
            } else null,
            contentDescription = "open toolbar",
            modifier = Modifier
                .height((BUTTON_SIZE + 1).dp)
                .padding(bottom = 1.dp)
        )
    }
}

fun presentlyUsedToolIcon(mode: Mode, pen: Pen): Int {
    return when (mode) {
        Mode.Draw -> {
            when (pen) {
                Pen.BALLPEN -> R.drawable.ballpen
                Pen.REDBALLPEN -> R.drawable.ballpenred
                Pen.BLUEBALLPEN -> R.drawable.ballpenblue
                Pen.GREENBALLPEN -> R.drawable.ballpengreen
                Pen.FOUNTAIN -> R.drawable.fountain
                Pen.BRUSH -> R.drawable.brush
                Pen.MARKER -> R.drawable.marker
                Pen.PENCIL -> R.drawable.pencil
                Pen.DASHED -> R.drawable.line_dashed
            }
        }

        Mode.Erase -> R.drawable.eraser
        Mode.Select -> R.drawable.lasso
        Mode.Line -> R.drawable.line
    }
}

@Composable
@Preview(showBackground = true, widthDp = 1200)
fun ToolbarPreview() {
    val uiState = ToolbarUiState(
        isToolbarOpen = true,
        mode = Mode.Draw,
        pen = Pen.BALLPEN,
        penSettings = mapOf(
            Pen.BALLPEN.penName to PenSetting(5f, android.graphics.Color.BLACK),
            Pen.MARKER.penName to PenSetting(40f, android.graphics.Color.LTGRAY)
        ),
        pageNumberInfo = "3/12",
        notebookId = "dummy_book"
    )

    ToolbarContent(
        uiState = uiState,
        onToggleToolbar = {},
        onModeChange = {},
        onPenChange = {},
        onPenSettingChange = { _, _ -> },
        onEraserChange = {},
        onMenuToggle = {},
        onBackgroundSelectorToggle = {},
        onUndo = {},
        onRedo = {},
        onPaste = {},
        onResetView = {},
        onNavigateToLibrary = {},
        onNavigateToBugReport = {},
        onNavigateToPages = {},
        onNavigateToHome = {},
        onToggleScribbleToErase = {},
        onImagePicked = {},
        onExport = { _, _ -> "" },
        onBackgroundChange = { _, _ -> },
        onDrawingStateCheck = {}
    )
}