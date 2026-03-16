package com.ethran.notable.editor.ui.toolbar

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
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw


private fun isSelected(state: ToolbarUiState, penType: Pen): Boolean {
    return (state.mode == Mode.Draw || state.mode == Mode.Line) && state.pen == penType
}

private val SIZES_STROKES_DEFAULT = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)
private val SIZES_MARKER_DEFAULT = listOf("M" to 25f, "L" to 40f, "XL" to 60f, "XXL" to 80f)

@Composable
fun ToolbarContent(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onDrawingStateCheck: () -> Unit,
) {
    // Activity result launcher for picking images
    val pickMedia = rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
        uri?.let { onAction(ToolbarAction.ImagePicked(it)) }
    }

    // On exit or change of toolbar states, check if we should allow raw drawing
    LaunchedEffect(uiState.isBackgroundSelectorModalOpen, uiState.isMenuOpen) {
        onDrawingStateCheck()
    }

    if (uiState.isBackgroundSelectorModalOpen) {
        BackgroundSelector(
            initialPageBackgroundType = uiState.backgroundType,
            initialPageBackground = uiState.backgroundPath,
            initialPageNumberInPdf = uiState.backgroundPageNumber,
            notebookId = uiState.notebookId,
            pageNumberInBook = uiState.currentPageNumber,
            onChange = { type, path -> onAction(ToolbarAction.BackgroundChanged(type, path)) },
            onClose = { onAction(ToolbarAction.ToggleBackgroundSelector(false)) }
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
                    onSelect = { onAction(ToolbarAction.ToggleToolbar) },
                    vectorIcon = FeatherIcons.EyeOff,
                    contentDescription = "close toolbar"
                )

                VerticalDivider()

                // Pens
                PenToolbarButton(
                    pen = Pen.BALLPEN,
                    icon = R.drawable.ballpen,
                    isSelected = isSelected(uiState, Pen.BALLPEN),
                    onSelect = { onAction(ToolbarAction.ChangePen(Pen.BALLPEN)) },
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = uiState.penSettings[Pen.BALLPEN.penName] ?: PenSetting(
                        5f,
                        android.graphics.Color.BLACK
                    ),
                    onChangeSetting = { onAction(ToolbarAction.ChangePenSetting(Pen.BALLPEN, it)) })

                if (!GlobalAppSettings.current.monochromeMode) {
                    listOf(
                        Triple(Pen.REDBALLPEN, R.drawable.ballpenred, android.graphics.Color.RED),
                        Triple(
                            Pen.BLUEBALLPEN,
                            R.drawable.ballpenblue,
                            android.graphics.Color.BLUE
                        ),
                        Triple(
                            Pen.GREENBALLPEN,
                            R.drawable.ballpengreen,
                            android.graphics.Color.GREEN
                        )
                    ).forEach { (pen, icon, defaultColor) ->
                        PenToolbarButton(
                            pen = pen,
                            icon = icon,
                            isSelected = isSelected(uiState, pen),
                            onSelect = { onAction(ToolbarAction.ChangePen(pen)) },
                            sizes = SIZES_STROKES_DEFAULT,
                            penSetting = uiState.penSettings[pen.penName] ?: PenSetting(
                                5f,
                                defaultColor
                            ),
                            onChangeSetting = { onAction(ToolbarAction.ChangePenSetting(pen, it)) },
                        )
                    }
                }

                if (GlobalAppSettings.current.neoTools) {
                    PenToolbarButton(
                        pen = Pen.PENCIL,
                        icon = R.drawable.pencil,
                        isSelected = isSelected(uiState, Pen.PENCIL),
                        onSelect = { onAction(ToolbarAction.ChangePen(Pen.PENCIL)) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.PENCIL.penName] ?: PenSetting(
                            5f,
                            android.graphics.Color.BLACK
                        ),
                        onChangeSetting = {
                            onAction(
                                ToolbarAction.ChangePenSetting(
                                    Pen.PENCIL,
                                    it
                                )
                            )
                        }
                    )

                    PenToolbarButton(
                        pen = Pen.BRUSH,
                        icon = R.drawable.brush,
                        isSelected = isSelected(uiState, Pen.BRUSH),
                        onSelect = { onAction(ToolbarAction.ChangePen(Pen.BRUSH)) },
                        sizes = SIZES_STROKES_DEFAULT,
                        penSetting = uiState.penSettings[Pen.BRUSH.penName] ?: PenSetting(
                            5f,
                            android.graphics.Color.BLACK
                        ),
                        onChangeSetting = {
                            onAction(
                                ToolbarAction.ChangePenSetting(
                                    Pen.BRUSH,
                                    it
                                )
                            )
                        }
                    )
                }
                PenToolbarButton(
                    pen = Pen.FOUNTAIN,
                    icon = R.drawable.fountain,
                    isSelected = isSelected(uiState, Pen.FOUNTAIN),
                    onSelect = { onAction(ToolbarAction.ChangePen(Pen.FOUNTAIN)) },
                    sizes = SIZES_STROKES_DEFAULT,
                    penSetting = uiState.penSettings[Pen.FOUNTAIN.penName] ?: PenSetting(
                        5f,
                        android.graphics.Color.BLACK
                    ),
                    onChangeSetting = {
                        onAction(
                            ToolbarAction.ChangePenSetting(
                                Pen.FOUNTAIN,
                                it
                            )
                        )
                    },
                )

                LineToolbarButton(
                    unSelect = { onAction(ToolbarAction.ChangeMode(Mode.Draw)) },
                    icon = R.drawable.line,
                    isSelected = uiState.mode == Mode.Line,
                    onSelect = { onAction(ToolbarAction.ChangeMode(Mode.Line)) },
                )

                VerticalDivider()

                PenToolbarButton(
                    pen = Pen.MARKER,
                    icon = R.drawable.marker,
                    isSelected = isSelected(uiState, Pen.MARKER),
                    onSelect = { onAction(ToolbarAction.ChangePen(Pen.MARKER)) },
                    sizes = SIZES_MARKER_DEFAULT,
                    penSetting = uiState.penSettings[Pen.MARKER.penName] ?: PenSetting(
                        40f,
                        android.graphics.Color.LTGRAY
                    ),
                    onChangeSetting = { onAction(ToolbarAction.ChangePenSetting(Pen.MARKER, it)) }
                )

                VerticalDivider()

                EraserToolbarButton(
                    isSelected = uiState.mode == Mode.Erase,
                    onSelect = { onAction(ToolbarAction.ChangeMode(Mode.Erase)) },
                    value = uiState.eraser,
                    onChange = { onAction(ToolbarAction.ChangeEraser(it)) },
                    toggleScribbleToErase = { onAction(ToolbarAction.ToggleScribbleToErase(it)) },
                    onMenuOpenChange = { onAction(ToolbarAction.UpdateMenuOpenTo(it)) },
                    isMenuOpen = uiState.isStrokeSelectionOpen
                )

                VerticalDivider()

                ToolbarButton(
                    isSelected = uiState.mode == Mode.Select,
                    onSelect = { onAction(ToolbarAction.ChangeMode(Mode.Select)) },
                    iconId = R.drawable.lasso,
                    contentDescription = "lasso"
                )

                VerticalDivider()

                ToolbarButton(
                    iconId = R.drawable.image,
                    contentDescription = "Image picker",
                    onSelect = { pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                )

                VerticalDivider()

                if (uiState.hasClipboard) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        contentDescription = "paste",
                        onSelect = { onAction(ToolbarAction.Paste) }
                    )
                    VerticalDivider()
                }

                if (uiState.showResetView) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.RefreshCcw,
                        contentDescription = "reset zoom and scroll",
                        onSelect = { onAction(ToolbarAction.ResetView) }
                    )
                    VerticalDivider()
                }

                Spacer(Modifier.weight(1f))

                VerticalDivider()

                ToolbarButton(
                    onSelect = { onAction(ToolbarAction.Undo) },
                    iconId = R.drawable.undo,
                    contentDescription = "undo"
                )
                ToolbarButton(
                    onSelect = { onAction(ToolbarAction.Redo) },
                    iconId = R.drawable.redo,
                    contentDescription = "redo"
                )

                VerticalDivider()

                if (uiState.notebookId != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(35.dp)
                            .padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = uiState.pageNumberInfo,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.noRippleClickable { onAction(ToolbarAction.NavigateToPages) },
                            textAlign = TextAlign.Center
                        )
                    }
                    VerticalDivider()
                }

                ToolbarButton(
                    iconId = R.drawable.home,
                    contentDescription = "library",
                    onSelect = { onAction(ToolbarAction.NavigateToHome) }
                )

                VerticalDivider()

                Column {
                    ToolbarButton(
                        onSelect = { onAction(ToolbarAction.ToggleMenu) },
                        iconId = R.drawable.menu,
                        contentDescription = "menu"
                    )
                    if (uiState.isMenuOpen) {
                        ToolbarMenu(
                            uiState = uiState,
                            onAction = onAction
                        )
                    }
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
            onSelect = { onAction(ToolbarAction.ToggleToolbar) },
            iconId = presentlyUsedToolIcon(uiState.mode, uiState.pen),
            penColor = if (uiState.mode != Mode.Erase) uiState.penSettings[uiState.pen.penName]?.color?.let {
                Color(
                    it
                )
            } else null,
            contentDescription = "open toolbar",
            modifier = Modifier
                .height((BUTTON_SIZE + 1).dp)
                .padding(bottom = 1.dp)
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(0.5.dp)
            .background(Color.Black)
    )
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
        onAction = {},
        onDrawingStateCheck = {}
    )
}