package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.sync.SyncBadge

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookCard(
    modifier: Modifier = Modifier,
    bookId: String,
    title: String,
    pageIds: List<String>,
    openPageId: String?,
    syncBadge: SyncBadge? = null,
    onOpen: (bookId: String, pageId: String) -> Unit,
    onOpenSettings: (bookId: String) -> Unit,
    onPreviewMissing: (String) -> Unit = {},
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
                        onLongClick = { onOpenSettings(bookId) }),
                pageId = pageId,
                onPreviewMissing = onPreviewMissing
            )
        }
        Text(
            text = pageIds.size.toString(),
            modifier = Modifier
                .background(Color.Black)
                .padding(5.dp),
            color = Color.White
        )
        syncBadge?.iconOrNull()?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = "Sync status: ${syncBadge.name}",
                tint = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.White, CircleShape)
                    .padding(2.dp)
                    .size(16.dp)
            )
        }
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

/** Icon for the corner badge, or null when there is nothing worth showing. */
private fun SyncBadge.iconOrNull(): ImageVector? = when (this) {
    SyncBadge.SYNCED -> Icons.Default.CloudDone
    SyncBadge.NOT_SYNCED -> Icons.Default.CloudOff
    SyncBadge.SCHEDULED -> Icons.Default.Schedule
    SyncBadge.SYNCING -> Icons.Default.Sync
    SyncBadge.REMOTE_AHEAD -> Icons.Default.CloudDownload
    SyncBadge.ERROR -> Icons.Default.ErrorOutline
}