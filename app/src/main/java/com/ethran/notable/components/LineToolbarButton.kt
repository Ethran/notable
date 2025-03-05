package com.ethran.notable.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log

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
                    Log.d(TAG, "Deselecting line")
                    unSelect()
                } else {
                    // Otherwise, select it
                    Log.d(TAG, "Selecting line")
                    onSelect()
                }
            },
            penColor = Color.LightGray,
            iconId = icon,
            contentDescription = "Lines!"
        )
    }
}