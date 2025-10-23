package com.ethran.notable.ui.dialogs


import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.TAG
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.getLinkedFilesDir
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.PagePreview
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch

@ExperimentalComposeUiApi
@Composable
fun NotebookConfigDialog(bookId: String, onClose: () -> Unit) {
    val bookRepository = BookRepository(LocalContext.current)
    val book by bookRepository.getByIdLive(bookId).observeAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current

    if (book == null) return

    var bookTitle by remember {
        mutableStateOf(book!!.title)
    }
    val formattedCreatedAt =
        remember { DateFormat.format("dd MMM yyyy HH:mm", book!!.createdAt) }
    val formattedUpdatedAt =
        remember { DateFormat.format("dd MMM yyyy HH:mm", book!!.updatedAt) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showBackgroundSelector by remember { mutableStateOf(false) }


    var bookFolder by remember { mutableStateOf(book?.parentFolderId) }


    if (showBackgroundSelector) {
        BackgroundSelector(
            initialPageBackgroundType = book!!.defaultBackgroundType,
            initialPageBackground = book!!.defaultBackground,
            isNotebookBgSelector = true,
            notebookId = book!!.id,
            onChange = { backgroundType, background ->
                if (background == null) {
                    if (book!!.defaultBackgroundType != backgroundType) {
                        val updatedBook = book!!.copy(
                            defaultBackgroundType = backgroundType
                        )
                        bookRepository.update(updatedBook)
                    }
                } else if (book!!.defaultBackgroundType != backgroundType || book!!.defaultBackground != background) {
                    val updatedBook = book!!.copy(
                        defaultBackgroundType = backgroundType,
                        defaultBackground = background
                    )
                    bookRepository.update(updatedBook)
                }
            }
        ) {
            showBackgroundSelector = false
        }
    }
    // Confirmation Dialog for Deletion
    if (showDeleteDialog) {
        ShowSimpleConfirmationDialog(
            title = "Confirm Deletion",
            message = "Are you sure you want to delete \"${book!!.title}\"?",
            onConfirm = {
                bookRepository.delete(bookId)
                showDeleteDialog = false
                onClose()
            },
            onCancel = {
                showDeleteDialog = false
            }
        )
        return
    }
    // Confirmation Dialog for Deletion
    if (showExportDialog) {
        ShowExportDialog(
            snackManager = snackManager,
            bookId = bookId,
            context = context,
            onConfirm = {
                showExportDialog = false
                onClose()
            },
            onCancel = {
                showExportDialog = false
            }
        )
        return
    }
    // Folder Selection Dialog
    if (showMoveDialog) {

        ShowFolderSelectionDialog(
            book = book!!,
            notebookName = book!!.title,
            initialFolderId = book!!.parentFolderId,
            onCancel = { showMoveDialog = false },
            onConfirm = { selectedFolder ->
                showMoveDialog = false
                onClose()
                Log.i(TAG, "folder: $selectedFolder")
                val updatedBook = book!!.copy(parentFolderId = selectedFolder)
                bookFolder = selectedFolder
                scope.launch {
                    // be careful, not to cause race condition.
                    bookRepository.update(updatedBook)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = {
            onClose()
        }
    ) {
        val focusManager = LocalFocusManager.current

        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            // Header Section
            Row(Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier
                        .size(200.dp, 250.dp)
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    val pageId = book!!.pageIds[0]
                    PagePreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Black, RectangleShape), pageId
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    /* -------------- Title Field -----------*/
                    Row {
                        Text(
                            text = stringResource(R.string.details_notebook_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.width(20.dp))
                        BasicTextField(
                            value = bookTitle,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Light,
                                fontSize = 24.sp
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            onValueChange = { bookTitle = it },
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier
                                .background(Color(230, 230, 230, 255))
                                .padding(10.dp, 0.dp)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        Log.i(TAG, "loose focus")
                                        if (book!!.title != bookTitle) {
                                            val updatedBook = book!!.copy(title = bookTitle)
                                            bookRepository.update(updatedBook)
                                        }
                                    }
                                }


                        )
                    }
                    /* -------------- Template selection -----------*/
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.details_notebook_default_background_template),
                        )
                        Spacer(modifier = Modifier.width(40.dp))
                        Button(
                            onClick = { showBackgroundSelector = !showBackgroundSelector },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(Color.White.toArgb()),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color.Black)
                        ) {
                            val typeName =
                                when (BackgroundType.fromKey(book?.defaultBackgroundType ?: "")) {
                                    BackgroundType.AutoPdf -> "Observe Pdf"
                                    BackgroundType.CoverImage -> "Cover Image"
                                    BackgroundType.Image -> "Image"
                                    BackgroundType.ImageRepeating -> "Repeating Image"
                                    BackgroundType.Native -> "Native"
                                    is BackgroundType.Pdf -> "Static pdf Page"
                                }
                            Text(
                                text = typeName,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowCircleRight,
                                contentDescription = "Expand selector",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    /* -------------- Linking to external files -----------*/


                    val defaultPath = getLinkedFilesDir().toUri().toString()
                    NotebookLinkRow(
                        isLinkedInit = book!!.linkedExternalUri != null,
                        linkedUriInit = book!!.linkedExternalUri,
                        defaultPath = defaultPath,
                        onLinkChanged = { newUri ->
                            val updated = book!!.copy(linkedExternalUri = newUri)
                            bookRepository.update(updated)
                        }
                    )

                    /* -------------- Other book info -----------*/
                    Text(stringResource(R.string.details_notebook_pages, book!!.pageIds.size))
                    Text("Size: TODO!")
                    Row {
                        Text(stringResource(R.string.details_notebook_in_folder))
                        BreadCrumb(folderId = bookFolder, fontSize = 16) { }
                    }
                    Text(stringResource(R.string.details_notebook_created, formattedCreatedAt))
                    Text(stringResource(R.string.details_notebook_last_updated, formattedUpdatedAt))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Grid Actions Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ActionButton(stringResource(R.string.details_notebook_buttons_delete)) {
                    showDeleteDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_move)) {
                    showMoveDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_export)) {
                    showExportDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_copy)) {
                    scope.launch {
                        snackManager.displaySnack(
                            SnackConf(text = "Not implemented!", duration = 2000)
                        )
                    }
                }

            }
        }

    }

}

@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(100.dp, 40.dp)
            .background(Color.LightGray, RectangleShape)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}


@Composable
fun NotebookLinkRow(
    isLinkedInit: Boolean,
    linkedUriInit: String?,
    defaultPath: String,
    onLinkChanged: (String?) -> Unit
) {
    var isLinked by remember { mutableStateOf(isLinkedInit) }

    val linkText = linkedUriInit?.let {
        if (it.length > 32) "${it.take(5)}...${it.takeLast(7)}/\${bookTitle}" else it
    } ?: "none"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.details_notebook_linked_to, linkText),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLinked) {
            Button(
                onClick = {
                    isLinked = false
                    onLinkChanged(null)
                },
                modifier = Modifier
                    .weight(0.3f, fill = false)
            ) {
                Text("Unlink")
            }
        } else {
            Button(
                onClick = {
                    isLinked = true
                    onLinkChanged(defaultPath)
                },
                modifier = Modifier
                    .weight(0.3f, fill = false)
            ) {
                Text("Link")
            }
        }
    }
}