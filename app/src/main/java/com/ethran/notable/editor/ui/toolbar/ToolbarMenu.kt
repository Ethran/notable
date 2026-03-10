package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.noRippleClickable

/**
 * Menu for the toolbar, providing export options and other page-level actions.
 * Centralizes actions via [ToolbarAction].
 */
@Composable
fun ToolbarMenu(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit
) {
    val context = LocalContext.current

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = { onAction(ToolbarAction.ToggleMenu) },
        offset = IntOffset(
            convertDpToPixel((-10).dp, context).toInt(),
            convertDpToPixel(50.dp, context).toInt()
        ),
        properties = PopupProperties(focusable = true),
    ) {
        ToolbarMenuContent(
            uiState = uiState,
            onAction = onAction
        )
    }
}

@Composable
private fun ToolbarMenuContent(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit
) {
    Column(
        Modifier
            .padding(bottom = (BUTTON_SIZE + 5).dp)
            .border(1.dp, Color.Black, RectangleShape)
            .background(Color.White)
            .width(IntrinsicSize.Max)
    ) {
        // Home / Library
        MenuItem(stringResource(R.string.home_view_name)) {
            onAction(ToolbarAction.NavigateToLibrary)
            onAction(ToolbarAction.ToggleMenu)
        }
        DividerCentered()

        // Page exports
        MenuItem(stringResource(R.string.export_page_to, "PDF")) {
            onAction(ToolbarAction.ExportPage(ExportFormat.PDF))
            onAction(ToolbarAction.ToggleMenu)
        }
        MenuItem(stringResource(R.string.export_page_to, "PNG")) {
            onAction(ToolbarAction.ExportPage(ExportFormat.PNG))
            onAction(ToolbarAction.ToggleMenu)
        }
        MenuItem(stringResource(R.string.export_page_to, "JPEG")) {
            onAction(ToolbarAction.ExportPage(ExportFormat.JPEG))
            onAction(ToolbarAction.ToggleMenu)
        }
        MenuItem(stringResource(R.string.export_page_to, "xopp")) {
            onAction(ToolbarAction.ExportPage(ExportFormat.XOPP))
            onAction(ToolbarAction.ToggleMenu)
        }
        DividerCentered()

        // Book exports
        if (uiState.notebookId != null) {
            MenuItem(stringResource(R.string.export_book_to, "PDF")) {
                onAction(ToolbarAction.ExportBook(ExportFormat.PDF))
                onAction(ToolbarAction.ToggleMenu)
            }
            MenuItem(stringResource(R.string.export_book_to, "PNG")) {
                onAction(ToolbarAction.ExportBook(ExportFormat.PNG))
                onAction(ToolbarAction.ToggleMenu)
            }
            MenuItem(stringResource(R.string.export_book_to, "xopp")) {
                onAction(ToolbarAction.ExportBook(ExportFormat.XOPP))
                onAction(ToolbarAction.ToggleMenu)
            }
            DividerCentered()
        }

        MenuItem(stringResource(R.string.clean_all_strokes)) {
            onAction(ToolbarAction.ClearAllStrokes)
            onAction(ToolbarAction.ToggleMenu)
        }
        DividerCentered()

        MenuItem(stringResource(R.string.change_background)) {
            onAction(ToolbarAction.ToggleBackgroundSelector(true))
            onAction(ToolbarAction.ToggleMenu)
        }

        MenuItem(stringResource(R.string.bug_report)) {
            onAction(ToolbarAction.NavigateToBugReport)
            onAction(ToolbarAction.ToggleMenu)
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = Color.Black
        )
    }
}

@Composable
private fun ColumnScope.DividerCentered() {
    Box(
        Modifier
            .fillMaxWidth(1f / 2f)
            .align(Alignment.CenterHorizontally)
            .height(0.5.dp)
            .background(Color(0xFF777777))
    )
}

@Composable
@Preview(showBackground = true)
fun ToolbarMenuPreview() {
    ToolbarMenuContent(
        uiState = ToolbarUiState(
            isMenuOpen = true,
            notebookId = "book1",
            mode = Mode.Draw,
            pen = Pen.BALLPEN,
            penSettings = mapOf(Pen.BALLPEN.penName to PenSetting(5f, android.graphics.Color.BLACK))
        ),
        onAction = {}
    )
}
