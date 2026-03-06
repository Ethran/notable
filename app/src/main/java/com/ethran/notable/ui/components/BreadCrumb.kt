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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.InkaTheme

@Composable
fun BreadCrumb(
    modifier: Modifier = Modifier,
    folders: List<Folder> = emptyList(),
    fontSize: Int = 20,
    onSelectFolderId: (String?) -> Unit
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.home_view_name),
            fontSize = fontSize.sp,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(end = 2.dp)
                .noRippleClickable { onSelectFolderId(null) })

        folders.forEach { f ->
            Icon(
                imageVector = Icons.Filled.ChevronRight, contentDescription = null,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = f.title,
                fontSize = fontSize.sp,
                textDecoration = TextDecoration.Underline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.noRippleClickable { onSelectFolderId(f.id) })
        }
    }
}

// TODO: Move it!!! And check the usage of it!!
fun getFolderList(context: Context, folderId: String?): List<Folder> {
    if (folderId == null) return emptyList()
    @Suppress("USELESS_ELVIS") val folder =
        FolderRepository(context).get(folderId) ?: return emptyList()
    val folderList = mutableListOf(folder)

    val parentId = folder.parentFolderId
    folderList.addAll(getFolderList(context, parentId))


    return folderList
}

fun getFolderList(appRepository: AppRepository, page: Page?): List<Folder> {
    val folderList = mutableListOf<Folder>()
    var currentFolderId = page?.parentFolderId
    while (currentFolderId != null) {
        val folder = appRepository.folderRepository.get(currentFolderId)
        if (folder != null) {
            folderList.add(folder)
            currentFolderId = folder.parentFolderId
        } else {
            currentFolderId = null
        }
    }
    folderList.reverse() // Parents first
    return folderList
}

@Preview(showBackground = true)
@Composable
fun BreadCrumbPreview() {
    InkaTheme {
        BreadCrumb(
            folders = listOf(
                Folder(id = "folder1", title = "Folder 1"),
                Folder(id = "folder2", title = "Folder 2"),
            ), fontSize = 20, onSelectFolderId = {})
    }
}
