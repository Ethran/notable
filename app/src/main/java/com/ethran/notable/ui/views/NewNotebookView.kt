package com.ethran.notable.ui.views

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.R
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.EInkButton
import com.ethran.notable.ui.dialogs.BackgroundSelectorContent
import com.ethran.notable.ui.dialogs.ShowFolderSelectionDialog
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.NewNotebookState
import com.ethran.notable.ui.viewmodels.NewNotebookViewModel

object NewNotebookDestination : NavigationDestination {
    override val route = "newNotebook"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String?): String =
        if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
}

@Composable
fun NewNotebookView(
    onBack: () -> Unit,
    onCreated: (pageId: String, bookId: String) -> Unit,
    viewModel: NewNotebookViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val breadcrumbFolders by viewModel.breadcrumbFolders.collectAsStateWithLifecycle()
    var showFolderPicker by remember { mutableStateOf(false) }

    val currentOnCreated by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) {
        viewModel.created.collect { currentOnCreated(it.pageId, it.bookId) }
    }

    if (showFolderPicker) {
        ShowFolderSelectionDialog(
            title = stringResource(R.string.new_notebook_choose_folder),
            initialFolderId = state.folderId,
            folderContents = viewModel::folderContents,
            onCancel = { showFolderPicker = false },
            onConfirm = {
                viewModel.setFolder(it)
                showFolderPicker = false
            })
    }

    // The screen is white, as the library is, so its controls take the light colors too.
    InkaTheme(darkTheme = false) {
        NewNotebookContent(
            state = state,
            breadcrumbFolders = breadcrumbFolders,
            onBack = onBack,
            onCreate = viewModel::create,
            onTitleChange = viewModel::setTitle,
            onChangeFolder = { showFolderPicker = true },
            backgroundSelector = {
                BackgroundSelectorContent(
                    initialPageBackgroundType = state.backgroundType,
                    initialPageBackground = state.background,
                    isNotebookBgSelector = true,
                    onChange = viewModel::setBackground,
                    modifier = Modifier.fillMaxWidth()
                )
            },
        )
    }
}

@Composable
private fun NewNotebookContent(
    state: NewNotebookState,
    breadcrumbFolders: List<Folder>,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onTitleChange: (String) -> Unit,
    onChangeFolder: () -> Unit,
    backgroundSelector: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.Black
                    )
                }
                Text(
                    text = stringResource(R.string.new_notebook_screen_title),
                    style = MaterialTheme.typography.h5,
                    modifier = Modifier.weight(1f)
                )
                EInkButton(onClick = onCreate, inverted = true) {
                    Text(stringResource(R.string.new_notebook_create), fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.details_notebook_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.details_notebook_in_folder))
                    BreadCrumb(
                        folders = breadcrumbFolders,
                        fontSize = 16,
                        modifier = Modifier.weight(1f)
                    ) { }
                    EInkButton(onClick = onChangeFolder) {
                        Text(stringResource(R.string.new_notebook_change_folder))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.details_notebook_default_background_template),
                        fontWeight = FontWeight.Bold,
                    )
                    backgroundSelector()
                }
            }
        }
    }
}

@Composable
private fun NewNotebookPreview() {
    NewNotebookContent(
        state = NewNotebookState(
            title = "New notebook",
            folderId = "f1",
            backgroundType = BackgroundType.Native.key,
            background = "lined",
        ),
        breadcrumbFolders = listOf(Folder(title = "Studies")),
        onBack = {},
        onCreate = {},
        onTitleChange = {},
        onChangeFolder = {},
        backgroundSelector = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .border(1.dp, Color.Black, RectangleShape)
            )
        },
    )
}

@Preview(name = "Tablet", showBackground = true, widthDp = 800, heightDp = 1000)
@Preview(name = "Phone", showBackground = true, widthDp = 340, heightDp = 700)
@Composable
private fun NewNotebookPreviewTabletAndPhone() = NewNotebookPreview()
