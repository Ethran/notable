package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.PlusCircle
import compose.icons.feathericons.Trash

@Composable
fun PageCard(
    pageId: String,
    pageIndex: Int,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onAddAfter: () -> Unit,
    modifier: Modifier = Modifier,
    touchModifier: Modifier = Modifier,
    isReorderDragging: Boolean = false,
) {
    Box(modifier = modifier) {
        PagePreview(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .border(if (isOpen) 2.dp else 1.dp, Color.Black, RectangleShape)
                .clickable(
                    enabled = isReorderDragging,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onOpen() }
                .then(touchModifier), pageId
        )

        // Current page header styling
        if (isOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(text = (pageIndex + 1).toString(), color = Color.White)
            }
        } else {
            Text(
                text = (pageIndex + 1).toString(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = Color.White
            )
        }

        // Bottom-right actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconPill(icon = FeatherIcons.Trash, contentDesc = "Delete page") {
                onDelete
            }
            IconPill(icon = FeatherIcons.Copy, contentDesc = "Duplicate page") {
                onDuplicate
            }
            IconPill(
                icon = FeatherIcons.PlusCircle, contentDesc = "Add page after"
            ) {
                onAddAfter
            }
        }
    }
}

/**
 * Small e‑ink-friendly icon pill button.
 */
@Composable
private fun IconPill(
    icon: ImageVector, contentDesc: String, onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFFFFFF))
            .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
            .then(Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp))
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon, contentDescription = contentDesc, tint = Color.Black
        )
    }
}