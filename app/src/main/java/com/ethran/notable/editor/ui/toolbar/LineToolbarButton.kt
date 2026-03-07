package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("LineToolbarButton")

@Composable
fun LineToolbarButton(
    icon: Int,
    isSelected: Boolean,
    onSelect: () -> Unit,
    unSelect: () -> Unit
) {

    Box {

        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) {
                    // If it's already selected, deselect it
                    log.d("Deselecting line")
                    unSelect()
                } else {
                    // Otherwise, select it
                    log.d("Selecting line")
                    onSelect()
                }
            },
            penColor = Color.LightGray,
            iconId = icon,
            contentDescription = "Lines!"
        )
    }
}