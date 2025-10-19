package com.ethran.notable.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.ui.noRippleClickable


@Composable
fun BreadCrumb(
    modifier: Modifier = Modifier,
    folderId: String? = null,
    fontSize: Int = 20,
    onSelectFolderId: (String?) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Library",
            fontSize = fontSize.sp,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(end = 2.dp)
                .noRippleClickable { onSelectFolderId(null) }
        )
//        ToolbarButton(iconId = R.drawable.home, onSelect = { onSelectFolderId(null) })

        if (folderId != null) {
            val folders: List<Folder> = getFolderList(context, folderId).reversed()

            folders.forEach { f ->
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null, // decorative
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                Text(
                    text = f.title,
                    fontSize = fontSize.sp,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.noRippleClickable { onSelectFolderId(f.id) }
                )
            }
        }
    }
}

fun getFolderList(context: Context, folderId: String): List<Folder> {
    @Suppress("USELESS_ELVIS")
    val folder = FolderRepository(context).get(folderId) ?: return emptyList()
    val folderList = mutableListOf(folder)

    val parentId = folder.parentFolderId
    if (parentId != null) {
        folderList.addAll(getFolderList(context, parentId))
    }

    return folderList
}