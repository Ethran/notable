package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.ui.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.ShipBook


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewQuickPage: (String?) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        uiState = uiState,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewQuickPage = { onCreateNewQuickPage(uiState.folderId) },
        onCreateNewFolder = viewModel::createNewFolder,
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = viewModel::onCreateNewNotebook,
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile,
        onPreviewMissing = viewModel::onPreviewRequested

    )
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    onPreviewMissing: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Topbar {
            Row(Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                BadgedBox(
                    badge = {
                        if (!uiState.isLatestVersion) Badge(
                            backgroundColor = Color.Black,
                            modifier = Modifier.offset((-12).dp, 10.dp)
                        )
                    }) {
                    Icon(
                        imageVector = FeatherIcons.Settings, contentDescription = "Settings",
                        Modifier
                            .padding(8.dp)
                            .noRippleClickable(onClick = onNavigateToSettings)
                    )
                }
            }
            Row(Modifier.padding(10.dp)) {
                BreadCrumb(
                    folders = uiState.breadcrumbFolders, onSelectFolderId = onNavigateToFolder
                )
            }

        }

        Column(Modifier.padding(10.dp)) {
            Spacer(Modifier.height(10.dp))

            FolderList(
                appRepository = appRepository,
                folders = uiState.folders,
                onNavigateToFolder = onNavigateToFolder,
                onCreateNewFolder = onCreateNewFolder
            )

            Spacer(Modifier.height(10.dp))
            ShowPagesRow(
                appRepository = appRepository,
                pages = uiState.singlePages,
                currentPageId = null,
                title = stringResource(R.string.home_quick_pages), onSelectPage = goToPage,
                showAddQuickPage = true,
                onCreateNewQuickPage = onCreateNewQuickPage,
                onPreviewMissing = onPreviewMissing
            )

            Spacer(Modifier.height(10.dp))

            NotebookGrid(
                appRepository = appRepository,
                exportEngine = exportEngine,
                books = uiState.books,
                isImporting = uiState.isImporting,
                onNavigateToEditor = onNavigateToEditor,
                onDeleteEmptyBook = onDeleteEmptyBook,
                onCreateNewNotebook = onCreateNewNotebook,
                onImportPdf = onImportPdf,
                onImportXopp = onImportXopp,
                onPreviewMissing = onPreviewMissing
            )
        }
    }


}

@Composable
fun FolderList(
    appRepository: AppRepository,
    folders: List<Folder>,
    onNavigateToFolder: (String) -> Unit, onCreateNewFolder: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        item {
            // Add new folder row
            Row(
                Modifier
                    .border(0.5.dp, Color.Black)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .noRippleClickable(onClick = onCreateNewFolder)
            ) {
                Icon(
                    imageVector = FeatherIcons.FolderPlus, contentDescription = "Add Folder",
                    Modifier.height(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(text = stringResource(R.string.home_add_new_folder))
            }
        }

        if (folders.isNotEmpty()) {
            items(folders) { folder ->
                var isFolderSettingsOpen by remember { mutableStateOf(false) }
                if (isFolderSettingsOpen) FolderConfigDialog(
                    appRepository.folderRepository,
                    folderId = folder.id,
                    onClose = {
                        log.i("Closing Directory Dialog")
                        isFolderSettingsOpen = false
                    })
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = { onNavigateToFolder(folder.id) },
                            onLongClick = { isFolderSettingsOpen = true })
                        .border(0.5.dp, Color.Black)
                        .padding(10.dp, 5.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder, contentDescription = "Folder",
                        Modifier.height(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = folder.title)
                }
            }
        }
    }
}

@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    books: List<Notebook>,
    isImporting: Boolean,
    onNavigateToEditor: (String, String) -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    onPreviewMissing: (String) -> Unit
) {
    Text(text = stringResource(R.string.home_notebooks))
    Spacer(Modifier.height(10.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.autoEInkAnimationOnScroll()
    ) {
        item {
            NotebookImportPanel(
                onCreateNewNotebook = onCreateNewNotebook,
                onImportPdf = onImportPdf,
                onImportXopp = onImportXopp
            )
        }

        if (books.isNotEmpty()) {
            items(books.reversed()) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!isImporting) {
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    onOpen = { bookId, pageId -> onNavigateToEditor(pageId, bookId) },
                    onOpenSettings = { isSettingsOpen = true },
                    onPreviewMissing = onPreviewMissing
                )

                if (isSettingsOpen) {
                    NotebookConfigDialog(
                        appRepository,
                        exportEngine = exportEngine,
                        bookId = book.id, onClose = { isSettingsOpen = false })
                }
            }
        }
    }
}

@Composable
fun NotebookImportPanel(
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }

    showPdfImportChoiceDialog?.let { uri ->
        PdfImportChoiceDialog(uri = uri, onCopy = { uri ->
            showPdfImportChoiceDialog = null
            onImportPdf(uri, /* copy= */ true)
        }, onObserve = {
            showPdfImportChoiceDialog = null
            onImportPdf(it, /* copy= */ false)
        }, onDismiss = { showPdfImportChoiceDialog = null })
    }


    Box(
        modifier = modifier
            .width(100.dp)
            .aspectRatio(3f / 4f)
            .border(1.dp, Color.Gray, RectangleShape),
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Create New Notebook Button (Top Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f) // Takes half the height
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable(onClick = onCreateNewNotebook)
            ) {
                Icon(
                    imageVector = FeatherIcons.FilePlus, contentDescription = "Create Notebook",
                    tint = Color.Gray, modifier = Modifier.size(40.dp)
                )
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) {
                    log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
                    return@rememberLauncherForActivityResult
                }
                try {

                    val mimeType = context.contentResolver.getType(uri)
                    log.d("Selected file mimeType: $mimeType, uri: $uri")
                    if (mimeType == "application/pdf" || uri.toString()
                            .endsWith(".pdf", ignoreCase = true)
                    ) {
                        showPdfImportChoiceDialog = uri
                    } else {
                        onImportXopp(uri)
                    }
                } catch (e: Exception) {
                    log.e("contentPicker failed: ${e.message}", e)
                    snackState.showOrUpdateSnack(SnackConf(text = "Importing failed: ${e.message}"))
                }
            }
            // Import Notebook (Bottom Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable {
                        launcher.launch(
                            arrayOf(
                                "application/x-xopp",
                                "application/gzip",
                                "application/octet-stream",
                                "application/pdf"
                            )
                        )
                    }

            ) {
                Icon(
                    imageVector = FeatherIcons.Upload,
                    contentDescription = "Import Notebook",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}


@Preview(
    showBackground = true,
    name = "Library - Default State",
    widthDp = 800,
    heightDp = 1200
)
@Composable
fun LibraryContentPreview() {
    // 1. Create a dummy UI state with mock data
    val mockUiState = LibraryUiState(
        folderId = null,
        isLatestVersion = true,
        isImporting = false,
        breadcrumbFolders = listOf(
            // Optional: Add mock breadcrumbs if you want to preview nested folder state
             Folder(id = "root", title = "Home", parentFolderId = null)
        ),
        folders = listOf(
            // Adjust constructor arguments based on your exact entity definition
            Folder(id = "folder_1", title = "Work Notes", parentFolderId = null),
            Folder(id = "folder_2", title = "Personal", parentFolderId = null)
        ),
        books = listOf(
            // Needs pageIds to render the card (empty books show a warning)
            Notebook(id = "book_1", title = "Meeting Minutes", pageIds = listOf("page1", "page2")),
            Notebook(id = "book_2", title = "Journal", pageIds = listOf("page3"))
        ),
        singlePages = emptyList() // Populate with mock Page() objects if you want to see Quick Pages
    )

    // 2. Render the stateless component with empty lambdas
//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}

@Suppress("UnusedVariable")
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Preview(showBackground = true, name = "Library - Update Available & Importing")
@Composable
fun LibraryContentUpdatePreview() {
    val mockUiState = LibraryUiState(
        folderId = "folder_1",
        isLatestVersion = false, // Will show the red badge on the settings icon
        isImporting = true,      // Will hide the delete warning for empty books
        breadcrumbFolders = emptyList(),
        folders = emptyList(),
        books = emptyList(),
        singlePages = emptyList()
    )

//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}