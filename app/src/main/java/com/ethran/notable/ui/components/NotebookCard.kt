package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


@Composable
fun NotebookCard(
    bookId: String,
    title: String,
    pageIds: List<String>,
    openPageId: String?,
    onOpen: (bookId: String, pageId: String) -> Unit,
    onOpenSettings: (bookId: String) -> Unit,
    modifier: Modifier = Modifier
) {

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .border(1.dp, Color.Black, RectangleShape)
            .background(Color.White)
            .clip(RoundedCornerShape(2))
    ) {
        Box {
            val pageId = pageIds[0]

            PagePreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .border(1.dp, Color.Black, RectangleShape)
                    .combinedClickable(
                        onClick = { onOpen(bookId, openPageId ?: pageIds[0]) },
                        onLongClick = { onOpenSettings(bookId) }), pageId)
        }
        Text(
            text = pageIds.size.toString(),
            modifier = Modifier
                .background(Color.Black)
                .padding(5.dp),
            color = Color.White
        )
        Text(
            text = title,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .padding(bottom = 8.dp) // Add some padding above the row
                .background(Color.White)
        )

    }
}