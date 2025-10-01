package com.ethran.notable.ui.views

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.ui.PageMenu
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.floatingEditor.FloatingEditorView
import com.ethran.notable.io.XoppFile
import com.ethran.notable.io.getFilePathFromUri
import com.ethran.notable.io.getPdfPageCount
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.ShowConfirmationDialog
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.ensureNotMainThread
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
import java.io.File
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

    var importInProgress = false

    var showFloatingEditor by remember { mutableStateOf(false) }
    var floatingEditorPageId by remember { mutableStateOf<String?>(null) }

    val snackManager = LocalSnackContext.current



    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }
    fun importPdf(uri: Uri, copy: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val snackText = if (copy) {
                "Importing PDF background (copy)"
            } else {
                "Setting up observer for PDF"
            }
            CoroutineScope(Dispatchers.IO).launch {
                importInProgress = true
                snackManager.showSnackDuring(snackText) {
                    handlePdfImport(
                        context, folderId, uri, copy
                    )
                }
                importInProgress = false
            }
        }
    }

    @Composable
    fun content() {
        Column {
            Text(
                text = "Do you want to copy or observe the PDF?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Observe: ", fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic
            )
            Text(
                text = "The app will set up a listener for changes to the file. Useful for files that change often (e.g., when using LaTeX).",
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• Copy: ", fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic
            )
            Text(
                text = "The app will copy the file to its database. Use this for safe and static storage.",
                fontSize = 14.sp
            )
        }
    }

    if (showPdfImportChoiceDialog != null) {
        ShowConfirmationDialog(
            title = "Import PDF Background",
            content = { content() },
            onConfirm = {
                showPdfImportChoiceDialog?.let { uri ->
                    showPdfImportChoiceDialog = null
                    importPdf(uri, copy = true)
                }
            },
            onCancel = {
                showPdfImportChoiceDialog?.let { uri ->
                    showPdfImportChoiceDialog = null
                    importPdf(uri, copy = false)
                }
            },
            confirmButtonText = "Copy",
            cancelButtonText = "Observe"
        )
    }



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
                    }
                ) {
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
                Modifier
                    .padding(10.dp)
            ) {
                BreadCrumb(folderId = folderId) { navController.navigate("library" + if (it == null) "" else "?folderId=${it}") }
            }
//           I do not know what the idea behind it was
//            // Add the new "Floating Editor" button here
//            Text(text = "Floating Editor",
//                textAlign = TextAlign.Center,
//                modifier = Modifier
//                    .noRippleClickable {
//                        val page = Page(
//                            notebookId = null,
//                            parentFolderId = folderId,
//                            nativeTemplate = appRepository.kvProxy.get(
//                                APP_SETTINGS_KEY, AppSettings.serializer()
//                            )?.defaultNativeTemplate ?: "blank"
//                        )
//                        appRepository.pageRepository.create(page)
//                        floatingEditorPageId = page.id
//                        showFloatingEditor = true
//                    }
//                    .padding(10.dp))

        }

        Column(
            Modifier.padding(10.dp)
        ) {

            Spacer(Modifier.height(10.dp))

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
                            }
                    ) {
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
                            folderId = folder.id,
                            onClose = {
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
            Spacer(Modifier.height(10.dp))
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
                            }
                    ) {
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
                                    .border(1.dp, Color.Black, RectangleShape),
                                pageId = pageId
                            )
                            if (isPageSelected) PageMenu(
                                pageId = pageId,
                                canDelete = true,
                                onClose = { isPageSelected = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(text = "Notebooks")
            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.autoEInkAnimationOnScroll()
            ) {
                item {
                    Box(
                        modifier = Modifier
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
                                        appRepository.bookRepository.create(
                                            Notebook(
                                                parentFolderId = folderId,
                                                defaultBackground = GlobalAppSettings.current.defaultNativeTemplate,
                                                defaultBackgroundType = BackgroundType.Native.key
                                            )
                                        )
                                    }
                            ) {
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
//                                    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
//                                    context.contentResolver.takePersistableUriPermission(uri, flag)
                                    val mimeType = context.contentResolver.getType(uri)
                                    Log.d(TAG, "Selected file mimeType: $mimeType, uri: $uri")
                                    if (mimeType == "application/pdf" || uri.toString()
                                            .endsWith(".pdf", ignoreCase = true)
                                    ) showPdfImportChoiceDialog = uri
                                    else CoroutineScope(Dispatchers.IO).launch {
                                        importInProgress = true
                                        snackManager.showSnackDuring("importing from xopp file") {
                                            XoppFile(context).importBook(uri, folderId)
                                        }
                                        importInProgress = false
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
                if (books?.isNotEmpty() == true) {
                    items(books!!.reversed()) { item ->
                        if (item.pageIds.isEmpty()) {
                            if (!importInProgress) {
                                ShowSimpleConfirmationDialog(
                                    title = "There is a book without pages!!!",
                                    message = "We suggest deleting book title \"${item.title}\", it was created at ${item.createdAt}. Do you want to do it?",
                                    onConfirm = {
                                        bookRepository.delete(item.id)
                                    },
                                    onCancel = { }
                                )
                            }
                            return@items
                        }
                        var isSettingsOpen by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .border(1.dp, Color.Black, RectangleShape)
                                .background(Color.White)
                                .clip(RoundedCornerShape(2))
                        ) {
                            Box {
                                val pageId = item.pageIds[0]

                                PagePreview(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .border(1.dp, Color.Black, RectangleShape)
                                        .combinedClickable(
                                            onClick = {
                                                val bookId = item.id
                                                val pageId = item.openPageId ?: item.pageIds[0]
                                                navController.navigate("books/$bookId/pages/$pageId")
                                            },
                                            onLongClick = {
                                                isSettingsOpen = true
                                            },
                                        ), pageId
                                )
                            }
                            Text(
                                text = item.pageIds.size.toString(),
                                modifier = Modifier
                                    .background(Color.Black)
                                    .padding(5.dp),
                                color = Color.White
                            )
                            Text(
                                text = item.title,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp) // Add some padding above the row
                                    .background(Color.White)
                            )
                        }
                        if (isSettingsOpen) NotebookConfigDialog(
                            bookId = item.id,
                            onClose = { isSettingsOpen = false })
                    }
                }
            }
        }
    }

// Add the FloatingEditorView here
    if (showFloatingEditor && floatingEditorPageId != null) {
        FloatingEditorView(
            navController = navController,
            pageId = floatingEditorPageId!!,
            onDismissRequest = {
                showFloatingEditor = false
                floatingEditorPageId = null
            }
        )
    }
}

fun handlePdfImport(context: Context, folderId: String?, uri: Uri, copyFile: Boolean = true) {
    Log.v(TAG, "Importing PDF from $uri")
    ensureNotMainThread("Importing")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flag)
    val subfolder = BackgroundType.Pdf(0).folderName
    val fileToSave = if (copyFile) copyBackgroundToDatabase(context, uri, subfolder)
    else {
        val fileName = getFilePathFromUri(context, uri)
        if (fileName == null) {
            Log.e(TAG, "File name is null")
            showHint(
                "Couldn't determine file path. Does the app have permission to read external storage?",
                duration = 5000
            )
            return
        } else File(fileName)
    } //content://com.android.providers.media.documents/document/document%3A1000000754

    val pageRepo = PageRepository(context)
    val bookRepo = BookRepository(context)

    val book = Notebook(
        title = fileToSave.nameWithoutExtension,
        parentFolderId = folderId,
        defaultBackground = fileToSave.toString(),
        defaultBackgroundType = BackgroundType.AutoPdf.key
    )
    bookRepo.createEmpty(book)

    val numberOfPages = getPdfPageCount(fileToSave.toString())

    for (i in 0 until numberOfPages) {
        val page = Page(
            notebookId = book.id,
            background = fileToSave.toString(),
            backgroundType = if (copyFile) BackgroundType.Pdf(i).key else BackgroundType.AutoPdf.key
        )
        pageRepo.create(page)
        bookRepo.addPage(book.id, page.id)
    }

}
