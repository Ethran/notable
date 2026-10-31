package com.ethran.notable.ui.dialogs


import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.R
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.copyLink
import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.ScaledDialog
import com.ethran.notable.ui.viewmodels.NotebookConfigViewModel

@Composable
fun NotebookConfigDialog(
    bookId: String,
    isNewlyCreated: Boolean = false,
    onOpenInEditor: ((pageId: String, bookId: String) -> Unit)? = null,
    onClose: () -> Unit,
    viewModel: NotebookConfigViewModel =
        hiltViewModel<NotebookConfigViewModel, NotebookConfigViewModel.Factory>(
            key = "NotebookConfig-$bookId"
        ) { it.create(bookId) },
) {
    val book = viewModel.book.collectAsStateWithLifecycle().value ?: return
    val breadcrumbFolders by viewModel.breadcrumbFolders.collectAsStateWithLifecycle()
    val snackManager = LocalSnackContext.current
    val context = LocalContext.current

    var bookTitle by remember { mutableStateOf(book.title) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showBackgroundSelector by remember { mutableStateOf(false) }

    if (showBackgroundSelector) {
        BackgroundSelector(
            initialPageBackgroundType = book.defaultBackgroundType,
            initialPageBackground = book.defaultBackground,
            isNotebookBgSelector = true,
            notebookId = book.id,
            onChange = viewModel::setDefaultBackground
        ) {
            showBackgroundSelector = false
        }
    }
    // Confirmation Dialog for Deletion
    if (showDeleteDialog) {
        ShowSimpleConfirmationDialog(
            title = "Confirm Deletion",
            message = "Are you sure you want to delete \"${book.title}\"?",
            onConfirm = {
                viewModel.delete()
                showDeleteDialog = false
                onClose()
            },
            onCancel = {
                showDeleteDialog = false
            })
        return
    }
    if (showExportDialog) {
        ShowExportDialog(
            onExport = { format ->
                viewModel.export(format)
                showExportDialog = false
                onClose()
            },
            onCancel = {
                showExportDialog = false
            })
        return
    }
    // Folder Selection Dialog
    if (showMoveDialog) {
        ShowFolderSelectionDialog(
            notebookName = book.title,
            initialFolderId = book.parentFolderId,
            folderContents = viewModel::folderContents,
            onCancel = { showMoveDialog = false },
            onConfirm = { selectedFolder ->
                showMoveDialog = false
                onClose()
                viewModel.moveTo(selectedFolder)
            })
    }

    ScaledDialog(onDismissRequest = onClose) {
        NotebookConfigContent(
            book = book,
            title = bookTitle,
            onTitleChange = { bookTitle = it },
            onTitleDone = { viewModel.setTitle(bookTitle) },
            breadcrumbFolders = breadcrumbFolders,
            isNewlyCreated = isNewlyCreated,
            onChangeBackground = { showBackgroundSelector = !showBackgroundSelector },
            onLinkedChange = viewModel::setLinked,
            onDelete = { showDeleteDialog = true },
            onMove = { showMoveDialog = true },
            onExport = { showExportDialog = true },
            onCopyLink = {
                copyLink(context, DeepLink.Book(bookId), book.title, snackManager::showOrUpdateSnack)
            },
            onDuplicate = viewModel::duplicate,
            onCreate = {
                viewModel.setTitle(bookTitle)
                onClose()
                book.pageIds.firstOrNull()?.let { onOpenInEditor?.invoke(it, bookId) }
            },
        )
    }
}

/** The dialog's card for [book]; [title] is the title being edited. */
@Composable
private fun NotebookConfigContent(
    book: Notebook,
    title: String,
    onTitleChange: (String) -> Unit,
    onTitleDone: () -> Unit,
    breadcrumbFolders: List<Folder>,
    isNewlyCreated: Boolean,
    onChangeBackground: () -> Unit,
    onLinkedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onCopyLink: () -> Unit,
    onDuplicate: () -> Unit,
    onCreate: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val formattedCreatedAt = remember(book.createdAt) { DateFormat.format("dd MMM yyyy HH:mm", book.createdAt) }
    val formattedUpdatedAt = remember(book.updatedAt) { DateFormat.format("dd MMM yyyy HH:mm", book.updatedAt) }

    val preview = @Composable {
        Box(
            modifier = Modifier
                .size(200.dp, 250.dp)
                .background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            book.pageIds.firstOrNull()?.let { pageId ->
                PagePreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .border(1.dp, Color.Black, RectangleShape), pageId
                )
            }
        }
    }

    val details = @Composable {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            /* -------------- Title Field -----------*/
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.details_notebook_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Spacer(Modifier.width(20.dp))
                BasicTextField(
                    value = title,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        fontSize = 24.sp
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text, imeAction = ImeAction.Done
                    ),
                    onValueChange = onTitleChange,
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(230, 230, 230, 255))
                        .padding(10.dp, 0.dp)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) onTitleDone()
                        }
                )
            }

            /* -------------- Template selection -----------*/
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.details_notebook_default_background_template),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onChangeBackground,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.Black)
                ) {
                    val typeName =
                        when (BackgroundType.fromKey(book.defaultBackgroundType)) {
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
            NotebookLinkRow(linkedUri = book.linkedExternalUri, onLinkedChange = onLinkedChange)

            /* -------------- Other book info -----------*/
            Text(stringResource(R.string.details_notebook_pages, book.pageIds.size))
            Text("Size: TODO!")
            Row {
                Text(stringResource(R.string.details_notebook_in_folder))
                BreadCrumb(folders = breadcrumbFolders, fontSize = 16) { }
            }
            Text(stringResource(R.string.details_notebook_created, formattedCreatedAt))
            Text(stringResource(R.string.details_notebook_last_updated, formattedUpdatedAt))
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .background(Color.White)
            .fillMaxWidth()
            .border(2.dp, Color.Black, RectangleShape)
            .padding(16.dp)
            .padding(top = 24.dp, bottom = 16.dp)
    ) {
        // A phone's dialog has no room for the preview beside the details.
        val narrow = maxWidth < 500.dp
        Column {
            if (narrow) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    preview()
                    Spacer(Modifier.height(16.dp))
                    details()
                }
            } else {
                Row {
                    preview()
                    Spacer(Modifier.width(16.dp))
                    details()
                }
            }

            Spacer(Modifier.height(16.dp))

            // Grid Actions Section
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(stringResource(R.string.details_notebook_buttons_delete), onDelete)
                ActionButton(stringResource(R.string.details_notebook_buttons_move), onMove)
                ActionButton(stringResource(R.string.details_notebook_buttons_export), onExport)
                ActionButton(stringResource(R.string.copy_link), onCopyLink)
                if (!isNewlyCreated) {
                    ActionButton(stringResource(R.string.details_notebook_buttons_duplicate), onDuplicate)
                }
                if (isNewlyCreated) {
                    ActionButton(stringResource(R.string.details_notebook_buttons_create)) {
                        focusManager.clearFocus()
                        onCreate()
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookConfigPreview(isNewlyCreated: Boolean) {
    NotebookConfigContent(
        book = Notebook(title = "Lecture notes", pageIds = listOf("p1", "p2", "p3")),
        title = "Lecture notes",
        onTitleChange = {},
        onTitleDone = {},
        breadcrumbFolders = listOf(Folder(title = "Studies")),
        isNewlyCreated = isNewlyCreated,
        onChangeBackground = {},
        onLinkedChange = {},
        onDelete = {},
        onMove = {},
        onExport = {},
        onCopyLink = {},
        onDuplicate = {},
        onCreate = {},
    )
}

@Preview(name = "Notebook settings", showBackground = true, widthDp = 800)
@Composable
private fun ExistingNotebookConfigPreview() = NotebookConfigPreview(isNewlyCreated = false)

@Preview(name = "New notebook", showBackground = true, widthDp = 800)
@Composable
private fun NewNotebookConfigPreview() = NotebookConfigPreview(isNewlyCreated = true)

@Preview(name = "Notebook settings, phone", showBackground = true, widthDp = 340)
@Composable
private fun PhoneNotebookConfigPreview() = NotebookConfigPreview(isNewlyCreated = false)

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
    linkedUri: String?,
    onLinkedChange: (Boolean) -> Unit
) {
    val linkText = linkedUri?.let {
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
        Button(
            onClick = { onLinkedChange(linkedUri == null) },
            modifier = Modifier.weight(0.3f, fill = false)
        ) {
            Text(if (linkedUri == null) "Link" else "Unlink")
        }
    }
}
