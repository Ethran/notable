package com.ethran.notable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.PenSetting
import com.ethran.notable.utils.convertDpToPixel

@Composable
fun StrokeMenu(
    value: PenSetting,
    onChange: (setting: PenSetting) -> Unit,
    onClose: () -> Unit,
    sizeOptions: List<Pair<String, Float>>,
    colorOptions: List<Color>,
) {
    val context = LocalContext.current

    Popup(
        offset = IntOffset(0, convertDpToPixel(43.dp, context).toInt()), onDismissRequest = {
            onClose()
        }, properties = PopupProperties(focusable = true), alignment = Alignment.TopCenter
    ) {

        Column {

            if(!GlobalAppSettings.current.monochromeMode) {
                // Color Selection Section
                Row(
                    Modifier
                        .background(Color.White)
                        .border(1.dp, Color.Black)
                        .height(IntrinsicSize.Max)
                ) {
                    colorOptions.map { color ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(color)
                                .border(
                                    3.dp,
                                    if (color == Color(value.color)) Color.Black else Color.Transparent
                                )
                                .clickable {
                                    onChange(
                                        PenSetting(
                                            strokeSize = value.strokeSize,
                                            color = android.graphics.Color.argb(
                                                (color.alpha * 255).toInt(),
                                                (color.red * 255).toInt(),
                                                (color.green * 255).toInt(),
                                                (color.blue * 255).toInt()
                                            )
                                        )
                                    )
                                }
                                .padding(8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier
                    .background(Color.White)
                    .border(1.dp, Color.Black)
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.Center
            ) {
                sizeOptions.forEach {
                    ToolbarButton(
                        text = it.first,
                        isSelected = value.strokeSize == it.second,
                        onSelect = {
                            onChange(
                                PenSetting(
                                    strokeSize = it.second,
                                    color = value.color
                                )
                            )
                        },
                        modifier = Modifier
                    )
                }
            }
        }

    }
}