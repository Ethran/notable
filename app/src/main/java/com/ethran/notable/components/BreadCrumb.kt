package com.ethran.notable.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.utils.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight

@Composable
fun BreadCrumb(folderId: String? = null, onSelectFolderId: (String?) -> Unit) {
    val context = LocalContext.current

    fun getFolderList(folderId: String): List<Folder> {
        @Suppress("USELESS_ELVIS")
        val folder = FolderRepository(context).get(folderId) ?: return emptyList()
        val folderList = mutableListOf(folder)

        val parentId = folder.parentFolderId
        if (parentId != null) {
            folderList.addAll(getFolderList(parentId))
        }

        return folderList
    }

    Row {
        Text(
            text = "Library",
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.noRippleClickable { onSelectFolderId(null) })
        if (folderId != null) {
            val folders = getFolderList(folderId).reversed()

            folders.map { f ->
                Icon(imageVector = FeatherIcons.ChevronRight, contentDescription = "")
                Text(
                    text = f.title,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.noRippleClickable { onSelectFolderId(f.id) })
            }
        }
    }
}