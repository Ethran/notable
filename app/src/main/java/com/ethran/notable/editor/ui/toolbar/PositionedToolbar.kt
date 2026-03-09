package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorViewModel

/**
 * Container for the Toolbar that handles its positioning (Top or Bottom).
 *
 * This component is now decoupled from navigation and engine logic,
 * delegating actions to the [EditorViewModel].
 */
@Composable
fun PositionedToolbar(
    viewModel: EditorViewModel,
    onDrawingStateCheck: () -> Unit
) {
    val position = GlobalAppSettings.current.toolbarPosition
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()

    val toolbar = @Composable {
        ToolbarContent(
            uiState = toolbarState,
            onAction = viewModel::onToolbarAction,
            onDrawingStateCheck = onDrawingStateCheck
        )
    }

    when (position) {
        AppSettings.Position.Top -> toolbar()
        AppSettings.Position.Bottom -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                toolbar()
            }
        }
    }
}