package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.deletePage
import com.ethran.notable.ui.noRippleClickable


@Composable
fun PageMenu(
    notebookId: String? = null,
    pageId: String,
    index: Int? = null,
    canDelete: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val appRepository = AppRepository(context)
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        PageRenameDialog(
            pageId = pageId,
            appRepository = appRepository,
            onClose = {
                showRenameDialog = false
                onClose()
            }
        )
        return
    }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max)
        ) {
            if (notebookId != null && index != null) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            appRepository.bookRepository.changePageIndex(
                                notebookId,
                                pageId,
                                index - 1
                            )
                        }
                ) {
                    Text("Move Left")
                }

                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            appRepository.bookRepository.changePageIndex(
                                notebookId,
                                pageId,
                                index + 1
                            )
                        }) {
                    Text("Move right")
                }
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            val book = appRepository.bookRepository.getById(notebookId)
                                ?: return@noRippleClickable
                            val page = book.newPage()
                            appRepository.pageRepository.create(page)
                            appRepository.bookRepository.addPage(notebookId, page.id, index + 1)
                        }) {
                    Text("Insert after")
                }
            }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        showRenameDialog = true
                    }) {
                Text("Rename")
            }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        appRepository.duplicatePage(pageId)
                    }) {
                Text("Duplicate")
            }
            if (canDelete) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            deletePage(context, pageId)
                        }) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
fun PageRenameDialog(
    pageId: String,
    appRepository: AppRepository,
    onClose: () -> Unit
) {
    val page = remember { appRepository.pageRepository.getById(pageId) }
    var pageName by remember { mutableStateOf(page?.name ?: "") }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Rename Page",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            BasicTextField(
                value = pageName,
                onValueChange = { pageName = it },
                textStyle = TextStyle(fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        page?.let {
                            appRepository.pageRepository.update(it.copy(name = pageName.ifBlank { null }))
                        }
                        onClose()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Gray, RectangleShape)
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (pageName.isEmpty()) {
                            Text("Page name", color = Color.Gray)
                        }
                        innerTextField()
                    }
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Box(
                    Modifier
                        .border(1.dp, Color.Black, RectangleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .noRippleClickable { onClose() }
                ) {
                    Text("Cancel")
                }
                Box(
                    Modifier
                        .border(1.dp, Color.Black, RectangleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .noRippleClickable {
                            page?.let {
                                appRepository.pageRepository.update(it.copy(name = pageName.ifBlank { null }))
                            }
                            onClose()
                        }
                ) {
                    Text("Save")
                }
            }
        }
    }
}

