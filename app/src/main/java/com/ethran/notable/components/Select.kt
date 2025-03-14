package com.ethran.notable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.ethran.notable.utils.noRippleClickable

@Composable
fun <T> SelectMenu(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }

    Box {
        Row {
            Text(text = options.find { it.first == value }?.second ?: "Undefined",
                fontWeight = FontWeight.Light,
                modifier = Modifier.noRippleClickable { isExpanded = true })

            Icon(
                Icons.Rounded.ArrowDropDown, contentDescription = "open select",
                modifier = Modifier.height(20.dp)
            )
        }
        if (isExpanded) Popup(onDismissRequest = { isExpanded = false }) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .border(0.5.dp, Color.Black, RectangleShape)
                    .background(Color.White)
            ) {
                options.map {
                    Text(text = it.second,
                        fontWeight = FontWeight.Light,
                        color = if (it.first == value) Color.White else Color.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (it.first == value) Color.Black else Color.White)
                            .padding(20.dp, 10.dp)
                            .noRippleClickable {
                                onChange(it.first)
                                isExpanded = false
                            }
                    )
                }
            }
        }
    }
}