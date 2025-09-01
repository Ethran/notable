package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.ui.components.BreadCrumb


@Composable
fun ShowFolderSelectionDialog(
    book: Notebook,
    notebookName: String,
    initialFolderId: String?,
    onCancel: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    val appRepository = AppRepository(LocalContext.current)


    var currentFolderId by remember { mutableStateOf(initialFolderId) }
    val availableFolders by appRepository.folderRepository.getAllInFolder(currentFolderId)
        .observeAsState()
    val currentFolderName = currentFolderId?.let {
        appRepository.folderRepository.get(it).title
    } ?: "Library"
    val parentFolder = appRepository.folderRepository.getParent(currentFolderId)

    Dialog(onDismissRequest = { onCancel() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose folder to move \"$notebookName\":",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            BreadCrumb(currentFolderId) { currentFolderId = it }
            // Folder List
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { currentFolderId = parentFolder }
                        .padding(2.dp)
                        .background(if (currentFolderId == parentFolder) Color.LightGray else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "..", fontSize = 16.sp, fontWeight = FontWeight.Normal)
                }

                // List Folders
                availableFolders?.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentFolderId = folder.id // Navigate into child folder
                            }
                            .padding(8.dp)
                            .background(if (currentFolderId == folder.id) Color.LightGray else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = folder.title, fontSize = 16.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }

            // Cancel and Confirm Buttons
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()
            ) {
                ActionButton("Cancel", onClick = onCancel)
                ActionButton("Confirm", onClick = { onConfirm(currentFolderId) })
            }
        }
    }
}