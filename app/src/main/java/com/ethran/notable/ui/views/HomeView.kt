package com.ethran.notable.ui.views

import android.content.Context
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.ui.PageMenu
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.utils.isLatestVersion
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@Composable
fun Library(navController: NavController, folderId: String? = null) {
    PageDataManager.cancelLoadingPages()

    val context = LocalContext.current

    val appRepository = AppRepository(LocalContext.current)

    val books by appRepository.bookRepository.getAllInFolder(folderId).observeAsState()
    val singlePages by appRepository.pageRepository.getSinglePagesInFolder(folderId)
        .observeAsState()
    val folders by appRepository.folderRepository.getAllInFolder(folderId).observeAsState()
    val bookRepository = BookRepository(LocalContext.current)

    var isLatestVersion by remember {
        mutableStateOf(true)
    }
    LaunchedEffect(key1 = Unit, block = {
        thread {
            isLatestVersion = isLatestVersion(context, true)
        }
    })


    Column(
        Modifier.fillMaxSize()
    ) {
        Topbar {
            Row(Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                BadgedBox(
                    badge = {
                        if (!isLatestVersion) Badge(
                            backgroundColor = Color.Black,
                            modifier = Modifier.offset((-12).dp, 10.dp)
                        )
                    }) {
                    Icon(
                        imageVector = FeatherIcons.Settings,
                        contentDescription = "",
                        Modifier
                            .padding(8.dp)
                            .noRippleClickable {
                                navController.navigate("settings")
                            })
                }
            }
            Row(
                Modifier.padding(10.dp)
            ) {
                BreadCrumb(folderId = folderId) { navController.navigate("library" + if (it == null) "" else "?folderId=${it}") }
            }

        }

        Column(
            Modifier.padding(10.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            FolderList(folders, navController, appRepository, folderId)

            Spacer(Modifier.height(10.dp))
            QuickPagesSection(singlePages, navController, appRepository, folderId)

            Spacer(Modifier.height(10.dp))
            NotebookGrid(context, books, navController, bookRepository, folderId)
        }
    }


}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun FolderList(
    folders: List<Folder>?,
    navController: NavController,
    appRepository: AppRepository,
    folderId: String?
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
                    .noRippleClickable {
                        val folder = Folder(parentFolderId = folderId)
                        appRepository.folderRepository.create(folder)
                    }) {
                Icon(
                    imageVector = FeatherIcons.FolderPlus,
                    contentDescription = "Add Folder Icon",
                    Modifier.height(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(text = "Add new folder")
            }
        }
        if (folders?.isNotEmpty() == true) {
            items(folders!!) { folder ->
                var isFolderSettingsOpen by remember { mutableStateOf(false) }
                if (isFolderSettingsOpen) FolderConfigDialog(
                    folderId = folder.id, onClose = {
                        Log.i(TAG, "Closing Directory Dialog")
                        isFolderSettingsOpen = false
                    })
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = {
                                navController.navigate("library?folderId=${folder.id}")
                            },
                            onLongClick = {
                                isFolderSettingsOpen = !isFolderSettingsOpen
                            },
                        )
                        .border(0.5.dp, Color.Black)
                        .padding(10.dp, 5.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder,
                        contentDescription = "folder icon",
                        Modifier.height(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = folder.title)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickPagesSection(
    singlePages: List<Page>?,
    navController: NavController,
    appRepository: AppRepository,
    folderId: String?
) {
    Text(text = "Quick pages")
    Spacer(Modifier.height(10.dp))

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        // Add the "Add quick page" button
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(100.dp)
                    .aspectRatio(3f / 4f)
                    .border(1.dp, Color.Gray, RectangleShape)
                    .noRippleClickable {
                        val page = Page(
                            notebookId = null,
                            background = GlobalAppSettings.current.defaultNativeTemplate,
                            backgroundType = BackgroundType.Native.key,
                            parentFolderId = folderId
                        )
                        appRepository.pageRepository.create(page)
                        navController.navigate("pages/${page.id}")
                    }) {
                Icon(
                    imageVector = FeatherIcons.FilePlus,
                    contentDescription = "Add Quick Page",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        // Render existing pages
        if (singlePages?.isNotEmpty() == true) {
            items(singlePages!!.reversed()) { page ->
                val pageId = page.id
                var isPageSelected by remember { mutableStateOf(false) }
                Box {
                    PagePreview(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    navController.navigate("pages/$pageId")
                                },
                                onLongClick = {
                                    isPageSelected = true
                                },
                            )
                            .width(100.dp)
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Black, RectangleShape), pageId = pageId
                    )
                    if (isPageSelected) PageMenu(
                        pageId = pageId, canDelete = true, onClose = { isPageSelected = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotebookGrid(
    context: Context,
    books: List<Notebook>?,
    navController: NavController,
    bookRepository: BookRepository,
    folderId: String?
) {
    var importInProgress = false

    Text(text = "Notebooks")
    Spacer(Modifier.height(10.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.autoEInkAnimationOnScroll()
    ) {
        item {
            NotebookImportPanel(
                context = context,
                bookRepository = bookRepository,
                parentFolderId = folderId,
                onStartImport = { importInProgress = true },
                onEndImport = { importInProgress = false }
            )
        }
        if (books?.isNotEmpty() == true) {
            items(books!!.reversed()) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!importInProgress) {
                        EmptyBookWarningHandler(emptyBook = book, onDelete = {
                            bookRepository.delete(book.id)
                        }, onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    onOpen = { bookId, pageId ->
                        navController.navigate("books/$bookId/pages/$pageId")
                    },
                    onOpenSettings = { isSettingsOpen = true })
                if (isSettingsOpen) NotebookConfigDialog(
                    bookId = book.id, onClose = { isSettingsOpen = false })
            }
        }
    }
}

@Composable
fun NotebookImportPanel(
    context: Context,
    bookRepository: BookRepository,
    parentFolderId: String?,
    onStartImport: () -> Unit,
    onEndImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackManager = LocalSnackContext.current

    fun onCreateNew() {
        bookRepository.create(
            Notebook(
                parentFolderId = parentFolderId,
                defaultBackground = GlobalAppSettings.current.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key
            )
        )
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val snackText = if (copy) {
                "Importing PDF background (copy)"
            } else {
                "Setting up observer for PDF"
            }
            onStartImport()
            snackManager.runWithSnack(snackText) {
                ImportEngine(context).import(
                    uri,
                    ImportOptions(folderId = parentFolderId, linkToExternalFile = !copy)
                )
            }
            onEndImport()
        }
    }

    fun onXoppFile(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            onStartImport()
            snackManager.showSnackDuring("importing from xopp file") {
                ImportEngine(context).import(
                    uri,
                    ImportOptions(folderId = parentFolderId)
                )
            }
            onEndImport()
        }
    }


    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }

    showPdfImportChoiceDialog?.let { uri ->
        PdfImportChoiceDialog(
            uri = uri, onCopy = { uri ->
            showPdfImportChoiceDialog = null
            onPdfFile(uri, /* copy= */ true)
        }, onObserve = { uri ->
            showPdfImportChoiceDialog = null
            onPdfFile(uri, /* copy= */ false)
        },
            onDismiss = {
                showPdfImportChoiceDialog = null
            }
        )
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
                    .noRippleClickable {
                        onCreateNew()

                    }) {
                Icon(
                    imageVector = FeatherIcons.FilePlus,
                    contentDescription = "Add Quick Page",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp),
                )
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) {
                    Log.w(
                        TAG,
                        "PickVisualMedia: uri is null (user cancelled or provider returned null)"
                    )
                    return@rememberLauncherForActivityResult
                }
                try {

                    val mimeType = context.contentResolver.getType(uri)
                    Log.d(TAG, "Selected file mimeType: $mimeType, uri: $uri")
                    if (mimeType == "application/pdf" || uri.toString()
                            .endsWith(".pdf", ignoreCase = true)
                    ) {
                        showPdfImportChoiceDialog = uri
                    } else {
                        onXoppFile(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "contentPicker failed: ${e.message}", e)
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Importing failed: ${e.message}"))
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
