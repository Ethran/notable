package com.ethran.notable.editor.ui.toolbar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.dialogs.BackgroundSelector

/**
 * Spec-driven toolbar: iterates a [ToolbarLayout] and renders each element through
 * [ToolbarElementView]. The layout is data; adding a tool means adding a registry
 * entry, not editing this file.
 */
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

    if (!uiState.isToolbarOpen) {
        CollapsedToolbarButton(uiState, onAction)
        return
    }

    // Snapshot read: setting changes (monochromeMode, neoTools, …) recompose the toolbar.
    val settings = GlobalAppSettings.current
    val layout = ToolbarLayout.DEFAULT // step 4 reads this from settings

    @Composable
    fun renderZone(ids: List<ToolbarElementId>) {
        for (id in ids) {
            val element = ToolbarElements.of(id)
            if (!element.visibleWhen(uiState, settings)) continue
            ToolbarElementView(
                element = element,
                uiState = uiState,
                onAction = onAction,
                onPickImage = {
                    pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height((BUTTON_SIZE + 2).dp)
            .padding(bottom = 1.dp)
    ) {
        if (settings.toolbarPosition == AppSettings.Position.Bottom) {
            HorizontalHairline()
        }
        Row(
            Modifier
                .background(Color.White)
                .height(BUTTON_SIZE.dp)
                .fillMaxWidth()
        ) {
            // Structural: the toggle is always first, never part of the layout.
            ToolbarElementView(
                element = ToolbarElements.of(ToolbarElementId.TOGGLE),
                uiState = uiState,
                onAction = onAction,
                onPickImage = {},
            )
            ToolbarVerticalDivider()

            // Left zone: scrolls horizontally.
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                renderZone(layout.scrollableIds())
            }

            // Right zone: pinned.
            Row {
                renderZone(layout.pinnedIds())
            }
        }

        HorizontalHairline()
    }
}

@Composable
private fun CollapsedToolbarButton(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val icon = ToolbarElements.presentlyUsedToolIcon(uiState)
    ToolbarButton(
        onSelect = { onAction(ToolbarAction.ToggleToolbar) },
        iconId = (icon as? IconRef.Drawable)?.resId,
        vectorIcon = (icon as? IconRef.Vector)?.imageVector,
        penColor = if (uiState.mode != Mode.Erase)
            uiState.penSettings[uiState.pen.penName]?.color?.let { Color(it) }
        else null,
        contentDescription = "open toolbar",
        modifier = Modifier
            .height((BUTTON_SIZE + 1).dp)
            .padding(bottom = 1.dp)
    )
}

@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black)
    )
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
