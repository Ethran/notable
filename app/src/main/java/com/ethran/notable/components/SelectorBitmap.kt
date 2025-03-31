package com.ethran.notable.components

import android.content.Context
import android.widget.Toast
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
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.noRippleClickable
import com.ethran.notable.utils.shareBitmap
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Scissors
import compose.icons.feathericons.Share2
import io.shipbook.shipbooksdk.Log

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
    if (selectionState.selectionDisplaceOffset == null) {
        Log.e(TAG, "SelectedBitmap: selectionDisplaceOffset is null")
        return
    }
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
                .offset {
                    if (selectionState.selectionStartOffset == null) return@offset IntOffset(
                        0,
                        0
                    ) // guard
                    selectionState.selectionStartOffset!! + selectionState.selectionDisplaceOffset!!
                }
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
                        //TODO: Sometimes its null, when handling images, for now I added some logs.
                        if (selectionState.selectionDisplaceOffset == null) {
                            Log.e(TAG, "selectionDisplaceOffset is null, probably was dissected")
                            Toast.makeText(
                                context,
                                "Please report issue if something went wrong with handling selection.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@detectDragGestures
                        }
                        selectionState.selectionDisplaceOffset =
                            selectionState.selectionDisplaceOffset!! + dragAmount.round()
                    }
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onDoubleClick = { controlTower.copySelection() }
                )
        )

        // TODO: improve this code

        val isSelectionResizable =
            selectionState.selectedImages?.count() == 1 &&
            selectionState.selectedStrokes.isNullOrEmpty()

        val buttonCount = if (isSelectionResizable) 6 else 4
        val buttonSize = 37;
        val toolbarPadding = 4;

        // If we can calculate offset of buttons show selection handling tools
        selectionState.selectionStartOffset?.let { startOffset ->
            selectionState.selectionDisplaceOffset?.let { displaceOffset ->
                val xPos = selectionState.selectionRect?.let { rect ->
                    (rect.left - rect.right) / 2 + buttonSize * buttonCount + (2 * toolbarPadding)
                } ?: 0
                val offset = startOffset + displaceOffset + IntOffset(x = -xPos, y = -100)
                // Overlay buttons near the selection box
                Row(
                    modifier = Modifier
                        .offset { offset }
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(toolbarPadding.dp)
                        .height(35.dp)
                ) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Share2,
                        isSelected = false,
                        onSelect = {
                            shareBitmap(context, editorState.selectionState.selectedBitmap!!)
                        },
                        modifier = Modifier.height(buttonSize.dp)
                    )
                    ToolbarButton(
                        iconId = R.drawable.delete,
                        isSelected = false,
                        onSelect = {
                            controlTower.deleteSelection()
                        },
                        modifier = Modifier.height(buttonSize.dp)
                    )
                    if (isSelectionResizable)
                        ToolbarButton(
                            iconId = R.drawable.plus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(10) },
                            modifier = Modifier.height(buttonSize.dp)
                        )
                    if (isSelectionResizable)
                        ToolbarButton(
                            iconId = R.drawable.minus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(-10) },
                            modifier = Modifier.height(buttonSize.dp)
                        )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Scissors,
                        isSelected = false,
                        onSelect = { controlTower.cutSelectionToClipboard() },
                        modifier = Modifier.height(buttonSize.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Copy,
                        isSelected = false,
                        onSelect = { controlTower.copySelection() },
                        modifier = Modifier.height(buttonSize.dp)
                    )
                }
            }
        }

    }
}