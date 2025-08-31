package com.ethran.notable.components.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.modals.BUTTON_SIZE
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.Eraser
import com.ethran.notable.utils.convertDpToPixel

@Composable
fun EraserToolbarButton(
    value: Eraser,
    onChange: (Eraser) -> Unit,
    onMenuOpenChange: ((Boolean) -> Unit)?,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    var isMenuOpen by remember { mutableStateOf(false) }

    if (onMenuOpenChange != null) {
        LaunchedEffect(isMenuOpen) {
            onMenuOpenChange(isMenuOpen)
        }
    }


    Box {

        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) isMenuOpen = !isMenuOpen
                else onSelect()
            },
            iconId = if (value == Eraser.PEN) R.drawable.eraser else R.drawable.eraser_select,
            contentDescription = "Eraser"
        )

        if (isMenuOpen) {
            Popup(
                offset = IntOffset(0, convertDpToPixel(43.dp, context).toInt()),
                onDismissRequest = {
                    isMenuOpen = false
                },
                properties = PopupProperties(focusable = true),
                alignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .border(1.dp, Color.Black)
                        .height(IntrinsicSize.Max)
                ) {
                    Row(Modifier.height(IntrinsicSize.Max).border(1.dp, Color.Black)) {
                        ToolbarButton(
                            iconId = R.drawable.eraser,
                            isSelected = value == Eraser.PEN,
                            onSelect = { onChange(Eraser.PEN) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                        ToolbarButton(
                            iconId = R.drawable.eraser_select,
                            isSelected = value == Eraser.SELECT,
                            onSelect = { onChange(Eraser.SELECT) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .padding(4.dp)
                            .height(26.dp)
                            .width(IntrinsicSize.Min)
                            .background(Color.White),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = "Scribble\nto Erase",
                            modifier = Modifier.padding(end = 6.dp),
                            style = TextStyle(color = Color.Black, fontSize = 13.sp)
                        )
                        val initialState = GlobalAppSettings.current.scribbleToEraseEnabled
                        var isChecked by remember { mutableStateOf(initialState) }

                        Box(
                            modifier = Modifier
                                .size(15.dp, 15.dp)
                                .border(1.dp, Color.Black)
                                .background(if (isChecked) Color.Black else Color.White)
                                .clickable {
                                    isChecked = !isChecked
                                    KvProxy(context).setAppSettings(
                                        GlobalAppSettings.current.copy(scribbleToEraseEnabled = isChecked)
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}