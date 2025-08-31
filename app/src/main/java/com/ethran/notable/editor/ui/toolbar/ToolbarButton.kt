package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.ui.noRippleClickable

@Composable
fun ToolbarButton(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    iconId: Int? = null,
    vectorIcon: ImageVector ? =null,
    imageVector: ImageVector? = null,
    text: String? = null,
    penColor: Color? = null,
    contentDescription: String = ""
) {
    Box(
        Modifier
            .then(modifier)
            .noRippleClickable {
                onSelect()
            }
            .background(
                color = if (isSelected) penColor ?: Color.Black else penColor ?: Color.Transparent,
                shape = if (!isSelected) CircleShape else RectangleShape
            )
            .padding(7.dp)

    ) {
        //needs simplification:
        if (iconId != null) {
            Icon(
                painter = painterResource(id = iconId),
                contentDescription,
                Modifier,
                if (penColor == Color.Black || penColor == Color.DarkGray) Color.White else if (isSelected) Color.White else Color.Black
            )
        }
        if (vectorIcon!=null){
            Icon(
                imageVector = vectorIcon,
                contentDescription,
                Modifier,
                if (penColor == Color.Black || penColor == Color.DarkGray) Color.White else if (isSelected) Color.White else Color.Black
            )
        }

        if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription,
                Modifier,
                if (isSelected) Color.White else Color.Black
            )
        }
        if (text != null) {
            Text(
                text = text,
                fontSize = 20.sp,
                color = if (isSelected) Color.White else Color.Black
            )
        }
    }
}