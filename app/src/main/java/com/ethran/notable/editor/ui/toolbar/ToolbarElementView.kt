package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ActionElement
import com.ethran.notable.editor.ui.toolbar.model.CustomElement
import com.ethran.notable.editor.ui.toolbar.model.CustomKind
import com.ethran.notable.editor.ui.toolbar.model.DividerElement
import com.ethran.notable.editor.ui.toolbar.model.EraserSubmenuSpec
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ModeElement
import com.ethran.notable.editor.ui.toolbar.model.PenElement
import com.ethran.notable.editor.ui.toolbar.model.ShapeElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElement
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.ui.components.OnOffSwitch
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.noRippleClickable

/**
 * The single generic renderer for toolbar elements: draws the button (via [ToolbarButton]),
 * handles selected state, and opens the element's declared submenu. Stateless except
 * transient popup-open state; all real mutation flows through [ToolbarAction].
 *
 * [onPickImage] exists because the image picker's activity-result launcher is Compose
 * infrastructure owned by ToolbarContent, not something a [ToolbarAction] can express.
 */
@Composable
fun ToolbarElementView(
    element: ToolbarElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onPickImage: () -> Unit,
) {
    when (element) {
        is DividerElement -> ToolbarVerticalDivider()

        is PenElement -> PenElementView(element, uiState, onAction)

        is ShapeElement ->
            // One shape (LINE) for now: the picker submenu is stubbed to a plain toggle,
            // matching the old LineToolbarButton (click again deselects back to Draw).
            ToolbarButton(
                isSelected = element.isSelected(uiState),
                onSelect = {
                    onAction(
                        ToolbarAction.ChangeMode(
                            if (element.isSelected(uiState)) Mode.Draw else Mode.Line
                        )
                    )
                },
                penColor = Color.LightGray,
                iconId = (element.icon as? IconRef.Drawable)?.resId,
                vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                contentDescription = element.contentDescription,
            )

        is ModeElement -> ModeElementView(element, uiState, onAction)

        is ActionElement ->
            ToolbarButton(
                isSelected = element.isSelected(uiState),
                onSelect = { onAction(element.action) },
                iconId = (element.icon as? IconRef.Drawable)?.resId,
                vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                contentDescription = element.contentDescription,
            )

        is CustomElement -> when (element.kind) {
            CustomKind.IMAGE_PICKER ->
                ToolbarButton(
                    iconId = (element.icon as? IconRef.Drawable)?.resId,
                    vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                    contentDescription = element.contentDescription,
                    onSelect = onPickImage,
                )

            CustomKind.PAGE_NAV ->
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

            CustomKind.ZOOM -> ZoomControl(uiState, onAction)

            CustomKind.MENU ->
                Column {
                    ToolbarButton(
                        onSelect = { onAction(ToolbarAction.ToggleMenu) },
                        iconId = (element.icon as? IconRef.Drawable)?.resId,
                        contentDescription = element.contentDescription,
                    )
                    if (uiState.isMenuOpen) {
                        ToolbarMenu(
                            uiState = uiState,
                            onAction = onAction
                        )
                    }
                }
        }
    }
}

@Composable
private fun ZoomControl(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val density = LocalDensity.current
    val isBottomToolbar = GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Bottom
    fun close() = onAction(ToolbarAction.ToggleZoomMenu(false))

    Box {
        ToolbarButton(
            text = "${ZoomPresets.label(uiState.zoomLevel)} ▾",
            contentDescription = stringResource(R.string.zoom_content_description),
            onSelect = { onAction(ToolbarAction.ToggleZoomMenu(!uiState.isZoomMenuOpen)) },
        )
        if (uiState.isZoomMenuOpen) ScaledPopup(
            alignment = if (isBottomToolbar) Alignment.BottomStart else Alignment.TopStart,
            offset = with(density) {
                IntOffset(0, (if (isBottomToolbar) -43 else 43).dp.roundToPx())
            },
            onDismissRequest = { close() },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 120.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Black)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                ZoomMenuItem(stringResource(R.string.zoom_fit_width)) {
                    onAction(ToolbarAction.FitWidth)
                    close()
                }
                ZoomMenuItem(stringResource(R.string.zoom_fit_page)) {
                    onAction(ToolbarAction.FitPage)
                    close()
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Black)
                )
                ZoomPresets.levels.forEach { level ->
                    ZoomMenuItem(ZoomPresets.label(level), selected = level == uiState.zoomLevel) {
                        onAction(ToolbarAction.SetZoom(level))
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomMenuItem(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (selected) Color.Black else Color.White)
            .noRippleClickable { onClick() }
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.Black,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun PenElementView(
    element: PenElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    var isStrokeMenuOpen by remember { mutableStateOf(false) }
    val isSelected = element.isSelected(uiState)
    val penSetting =
        uiState.penSettings[element.presetId] ?: element.setting.copy()

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) isStrokeMenuOpen = !isStrokeMenuOpen
                else onAction(ToolbarAction.ChangePen(element.presetId))
            },
            penColor = Color(penSetting.color),
            iconId = (element.icon as? IconRef.Drawable)?.resId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (isStrokeMenuOpen) {
            StrokeMenu(
                value = penSetting,
                onChange = { onAction(ToolbarAction.ChangePenSetting(element.presetId, it)) },
                onClose = { isStrokeMenuOpen = false },
                sizeOptions = element.submenu.sizeOptions,
                colorOptions = element.submenu.colorOptions,
            )
        }
    }
}

@Composable
private fun ModeElementView(
    element: ModeElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val isSelected = element.isSelected(uiState)
    val submenu = element.submenu

    // The eraser button reflects the active eraser type, not its static spec icon.
    val iconId =
        if (submenu is EraserSubmenuSpec) eraserIcon(uiState.eraser)
        else (element.icon as? IconRef.Drawable)?.resId

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected && submenu is EraserSubmenuSpec)
                    onAction(ToolbarAction.ToggleEraserManu(!uiState.isStrokeSelectionOpen))
                else if (!isSelected)
                    onAction(ToolbarAction.ChangeMode(element.mode))
            },
            iconId = iconId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (submenu is EraserSubmenuSpec && uiState.isStrokeSelectionOpen) {
            EraserSubmenu(
                spec = submenu,
                uiState = uiState,
                onAction = onAction,
            )
        }
    }
}

private fun eraserIcon(eraser: Eraser): Int =
    when (eraser) {
        Eraser.PEN -> R.drawable.eraser
        Eraser.SELECT -> R.drawable.eraser_select
    }

/** The eraser popup: eraser-type picker plus the global scribble-to-erase toggle. */
@Composable
private fun EraserSubmenu(
    spec: EraserSubmenuSpec,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val density = LocalDensity.current

    ScaledPopup(
        offset = with(density) {
            IntOffset(
                0,
                43.dp.roundToPx()
            )
        },
        onDismissRequest = {
            onAction(ToolbarAction.ToggleEraserManu(false))
        },
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(bottom = (BUTTON_SIZE + 5).dp)
                .background(Color.White)
                .border(1.dp, Color.Black)
                .height(IntrinsicSize.Max)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.toolbar_eraser),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .border(1.dp, Color.Black),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                spec.erasers.forEach { eraser ->
                    ToolbarButton(
                        iconId = eraserIcon(eraser),
                        isSelected = uiState.eraser == eraser,
                        onSelect = {
                            onAction(ToolbarAction.ChangeEraser(eraser))
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .background(Color.White),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = stringResource(
                        R.string.toolbar_scribble_to_erase_two_lined_short
                    ),
                    modifier = Modifier.padding(end = 6.dp),
                    style = TextStyle(
                        color = Color.Black,
                        fontSize = 13.sp
                    )
                )

                val initialState =
                    GlobalAppSettings.current.scribbleToEraseEnabled

                var isChecked by remember {
                    mutableStateOf(initialState)
                }

                Spacer(modifier = Modifier.width(15.dp))

                OnOffSwitch(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        isChecked = checked
                        onAction(
                            ToolbarAction.ToggleScribbleToErase(checked)
                        )
                    }
                )
            }
        }
    }
}

@Composable
internal fun ToolbarVerticalDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(0.5.dp)
            .background(Color.Black)
    )
}
