package com.ethran.notable.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.ethran.notable.R
import com.ethran.notable.TAG
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.modals.BUTTON_SIZE
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.noRippleClickable
import com.ethran.notable.utils.shareBitmap
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Scissors
import compose.icons.feathericons.Share2

val strokeStyle = androidx.compose.ui.graphics.drawscope.Stroke(
    width = 2f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
)

@Composable
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
fun SelectedBitmap(
    context: Context,
    editorState: EditorState,
    controlTower: EditorControlTower
) {
    val selectionState = editorState.selectionState
    if (selectionState.selectedBitmap == null) return

    var selectionDisplaceOffset =
        editorState.pageView.applyZoom(selectionState.selectionDisplaceOffset ?: return)
    val selectionRect =
        editorState.pageView.toScreenCoordinates(selectionState.selectionRect ?: return)
    val selectionStartOffset =
        editorState.pageView.applyZoom(selectionState.selectionStartOffset ?: IntOffset(0, 0))


    Box(
        Modifier
            .fillMaxSize()
            .noRippleClickable {
                controlTower.applySelectionDisplace()
                selectionState.reset()
                editorState.isDrawing = true
            }) {
        Image(
            bitmap = selectionState.selectedBitmap!!.asImageBitmap(),
            contentDescription = "Selection bitmap",
            modifier = Modifier
                .offset { selectionStartOffset + selectionDisplaceOffset }
                .drawBehind {
                    drawRect(
                        color = Color.Gray,
                        topLeft = Offset(0f, 0f),
                        size = size,
                        style = strokeStyle
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        selectionState.selectionDisplaceOffset =
                            editorState.pageView.removeZoom(
                                selectionDisplaceOffset + dragAmount.round()
                            )
                        selectionDisplaceOffset =
                            editorState.pageView.applyZoom(
                                selectionState.selectionDisplaceOffset ?: return@detectDragGestures
                            )
                    }
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onDoubleClick = { controlTower.duplicateSelection() }
                )
        )

        // TODO: improve this code

        val buttonCount = if (selectionState.isResizable()) 7 else 5
        val toolbarPadding = 4

        // If we can calculate offset of buttons show selection handling tools
        selectionStartOffset.let { startOffset ->
            selectionDisplaceOffset.let { displaceOffset ->
                // TODO: I think the toolbar is still not in the center.
                val xPos = selectionRect.let { rect ->
                    (rect.right - rect.left) / 2 - buttonCount * (BUTTON_SIZE + 5 * toolbarPadding)
                }
                val offset = startOffset + displaceOffset + IntOffset(x = xPos, y = -100)
                // Overlay buttons near the selection box
                Row(
                    modifier = Modifier
                        .offset { offset }
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(toolbarPadding.dp)
                        .height(BUTTON_SIZE.dp)
                ) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Share2,
                        isSelected = false,
                        onSelect = {
                            shareBitmap(context, editorState.selectionState.selectedBitmap!!)
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        iconId = R.drawable.delete,
                        isSelected = false,
                        onSelect = {
                            controlTower.deleteSelection()
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    if (selectionState.isResizable()) {
                        ToolbarButton(
                            iconId = R.drawable.plus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                        ToolbarButton(
                            iconId = R.drawable.minus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(-10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Scissors,
                        isSelected = false,
                        onSelect = { controlTower.cutSelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        isSelected = false,
                        onSelect = { controlTower.copySelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Copy,
                        isSelected = false,
                        onSelect = { controlTower.duplicateSelection() },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                }
            }
        }

    }
}