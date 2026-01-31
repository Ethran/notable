package com.ethran.notable.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.newPage
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.EditorView
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.io.IndexExporter
import com.ethran.notable.ui.components.Anchor
import com.ethran.notable.ui.components.QuickNav
import com.ethran.notable.ui.views.BugReportScreen
import com.ethran.notable.ui.views.Library
import com.ethran.notable.ui.views.PagesView
import com.ethran.notable.ui.views.SettingsView
import com.ethran.notable.ui.views.SystemInformationView
import com.ethran.notable.ui.views.WelcomeView
import com.ethran.notable.ui.views.hasFilePermission
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException


private val logRouter = ShipBook.getLogger("Router")

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@Composable
fun Router(intentData: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    var isQuickNavOpen by remember {
        mutableStateOf(false)
    }
    var currentPageId: String? by remember { mutableStateOf(null) }
    var deepLinkHandled by remember { mutableStateOf(false) }

    LaunchedEffect(isQuickNavOpen) {
        logRouter.d("Changing drawing state, isQuickNavOpen: $isQuickNavOpen")
        DrawCanvas.isDrawing.emit(!isQuickNavOpen)
    }

    // Handle deep links
    LaunchedEffect(intentData) {
        if (intentData == null || deepLinkHandled) return@LaunchedEffect
        if (!hasFilePermission(context)) return@LaunchedEffect

        deepLinkHandled = true
        handleDeepLink(context, navController, intentData)
    }

    val startDestination =
        if (GlobalAppSettings.current.showWelcome || !hasFilePermission(LocalContext.current)) "welcome"
        else "library?folderId={folderId}"
    Box(
        Modifier
            .fillMaxSize()
            .detectThreeFingerTouchToOpenQuickNav {
                // Save the page on which QuickNav was opened
                navController.currentBackStackEntry?.savedStateHandle?.set(
                    "quickNavSourcePageId", currentPageId
                )
                isQuickNavOpen = true
            }) {
        NavHost(
            navController = navController,
            startDestination = startDestination,

            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(
                route = "library?folderId={folderId}",
                arguments = listOf(navArgument("folderId") { nullable = true }),
            ) {
                Library(
                    navController = navController,
                    folderId = it.arguments?.getString("folderId"),
                )
                currentPageId = null
            }
            composable(
                route = "welcome",
            ) {
                WelcomeView(
                    navController = navController,
                )
                currentPageId = null
            }
            composable(
                route = "SystemInformationView",
            ) {
                SystemInformationView(
                    navController = navController,
                )
                currentPageId = null
            }
            composable(
                route = "books/{bookId}/pages/{pageId}",
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("pageId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId")!!
                // read last pageId saved in savedStateHandle or start argument
                val initialPageId = backStackEntry.savedStateHandle.get<String>("pageId")
                    ?: backStackEntry.arguments?.getString("pageId")!!
                currentPageId = initialPageId
                // make sure savedStateHandle has something set (on first access)
                backStackEntry.savedStateHandle["pageId"] = initialPageId

                EditorView(
                    navController = navController,
                    bookId = bookId,
                    pageId = initialPageId,
                    onPageChange = { newPageId ->
                        // SAVE new pageId in savedStateHandle - do not call navigate
                        backStackEntry.savedStateHandle["pageId"] = newPageId
                        if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 2) {
                            backStackEntry.savedStateHandle["pageChangesSinceJump"] = 1
                        } else if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 1) {
                            backStackEntry.savedStateHandle.remove<Int>("pageChangesSinceJump")
                            backStackEntry.savedStateHandle.remove<String>("quickNavSourcePageId")
                        }
                        currentPageId = newPageId
                        logRouter.d("Editor changed page -> saved pageId=$newPageId (no navigate, no recreate)")
                    })
            }
            composable(
                route = "pages/{pageId}",
                arguments = listOf(navArgument("pageId") {
                    type = NavType.StringType
                }),
            ) { backStackEntry ->
                currentPageId = backStackEntry.arguments?.getString("pageId")
                EditorView(
                    navController = navController,
                    bookId = null,
                    pageId = backStackEntry.arguments?.getString("pageId")!!,
                    onPageChange = { logRouter.e("onPageChange for quickPages! $it") })
            }
            composable(
                route = "books/{bookId}/pages",
                arguments = listOf(navArgument("bookId") {
                    /* configuring arguments for navigation */
                    type = NavType.StringType
                }),
            ) {
                PagesView(
                    navController = navController,
                    bookId = it.arguments?.getString("bookId")!!,
                )
            }
            composable(
                route = "settings",
            ) {
                SettingsView(navController = navController)
                currentPageId = null
            }
            composable(
                route = "bugReport",
            ) {
                BugReportScreen(navController = navController)
                currentPageId = null
            }
        }
        val quickNavSourcePageId =
            navController.currentBackStackEntry?.savedStateHandle?.get<String>("quickNavSourcePageId")
        if (isQuickNavOpen) {
            QuickNav(
                navController = navController,
                currentPageId = currentPageId,
                quickNavSourcePageId = quickNavSourcePageId,
                onClose = {
                    isQuickNavOpen = false
                    if (quickNavSourcePageId == currentPageId)
                    // User didn't use the QuickNav, so remove the savedStateHandle
                        navController.currentBackStackEntry?.savedStateHandle?.remove<String>("quickNavSourcePageId")
                    else
                    // user did change page with QuickNav, start counting page changes
                        navController.currentBackStackEntry?.savedStateHandle?.set(
                            "pageChangesSinceJump",
                            2
                        )

                    refreshScreen()
                },
            )
        }
        Anchor(
            navController = navController,
            currentPageId = currentPageId,
            quickNavSourcePageId = quickNavSourcePageId,
            onClose = { isQuickNavOpen = false },
        )
    }

}

/**
 * Detects a three-finger touch (simultaneous finger contacts) to open QuickNav.
 *
 */
private fun Modifier.detectThreeFingerTouchToOpenQuickNav(
    onOpen: () -> Unit
): Modifier = this.pointerInput(Unit) {
    while (true) {
        try {
            awaitPointerEventScope {
                // Wait for a DOWN that was not already consumed by children.
                val firstDown = try {
                    awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
                } catch (_: CancellationException) {
                    return@awaitPointerEventScope
                }

                // Only react to finger input; ignore stylus or other pointer types.
                if (firstDown.type != PointerType.Touch) {
                    // Drain without consuming until all pointers are up; then restart listening.
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                    } while (e.changes.any { it.pressed })
                    return@awaitPointerEventScope
                }

                var opened = false

                // Track until all pointers lift (single gesture life cycle).
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)

                    // Count currently pressed finger touches
                    val touches =
                        event.changes.filter { it.type == PointerType.Touch && it.pressed }

                    // Recognize three-finger touch once; consume only upon recognition
                    if (!opened && touches.size >= 3) {
                        opened = true
                        touches.take(3).forEach { it.consume() }
                        onOpen()
                    } else if (opened) {
                        // After recognition, keep consuming these touches to avoid bleed-through
                        touches.forEach { it.consume() }
                    }

                    // End when all pointers are up
                    if (event.changes.none { it.pressed }) break
                }
            }
        } catch (_: CancellationException) {
            // Pointer input was cancelled (e.g., recomposition);
            return@pointerInput
        } catch (e: Throwable) {
            logRouter.e("Router: Error in pointerInput", e)
        }
    }
}

/**
 * Handles deep link navigation for PKM systems integration.
 *
 * Supported URL formats:
 *
 * Navigation:
 * - notable://page-{uuid} - Open existing page
 * - notable://book-{uuid} - Open existing book
 *
 * Create:
 * - notable://new-folder?name=FolderName&parent=ParentFolderName - Create folder
 * - notable://new-book?name=BookName&folder=FolderName - Create book in folder
 * - notable://new-page/{uuid}?name=PageName - Create standalone quick page
 * - notable://new-page/{uuid}?name=PageName&folder=FolderName - Create quick page in folder
 * - notable://book/{bookId}/new-page/{uuid}?name=PageName - Create page in book
 *
 * Export:
 * - notable://export/page/{pageId}?format=pdf|png|jpg|xopp - Export page
 * - notable://export/book/{bookId}?format=pdf|png|xopp - Export book
 *
 * Utility:
 * - notable://sync-index - Force refresh the JSON index
 */
private suspend fun handleDeepLink(context: Context, navController: NavController, intentData: String) {
    try {
        val uri = Uri.parse(intentData)
        if (uri.scheme != "notable") {
            logRouter.w("Invalid deep link scheme: ${uri.scheme}")
            return
        }

        // Get the path - handle both host-based and path-based formats
        val path = uri.host ?: uri.path?.removePrefix("/") ?: return
        logRouter.d("Handling deep link path: $path")

        when {
            // Sync index: notable://sync-index
            path == "sync-index" -> {
                logRouter.d("Syncing index")
                withContext(Dispatchers.IO) {
                    IndexExporter.exportSync(context)
                }
            }

            // Create new folder: notable://new-folder?name=FolderName&parent=ParentFolderName
            path == "new-folder" -> {
                val folderName = uri.getQueryParameter("name") ?: "New Folder"
                val parentName = uri.getQueryParameter("parent")
                logRouter.d("Creating new folder: $folderName in parent: $parentName")
                withContext(Dispatchers.IO) {
                    createNewFolder(context, folderName, parentName)
                    IndexExporter.exportSync(context)
                }
            }

            // Create new book: notable://new-book?name=BookName&folder=FolderName
            path == "new-book" -> {
                val bookName = uri.getQueryParameter("name") ?: "New Notebook"
                val folderName = uri.getQueryParameter("folder")
                logRouter.d("Creating new book: $bookName in folder: $folderName")
                val bookId = withContext(Dispatchers.IO) {
                    val id = createNewBook(context, bookName, folderName)
                    IndexExporter.exportSync(context)
                    id
                }
                if (bookId != null) {
                    navController.navigate("books/$bookId/pages")
                }
            }

            // Export page: notable://export/page/{pageId}?format=pdf
            path.startsWith("export/page/") -> {
                val pageId = path.removePrefix("export/page/")
                val format = uri.getQueryParameter("format") ?: "png"
                logRouter.d("Exporting page $pageId as $format")
                withContext(Dispatchers.IO) {
                    exportPage(context, pageId, format)
                }
            }

            // Export book: notable://export/book/{bookId}?format=pdf
            path.startsWith("export/book/") -> {
                val bookId = path.removePrefix("export/book/")
                val format = uri.getQueryParameter("format") ?: "pdf"
                logRouter.d("Exporting book $bookId as $format")
                withContext(Dispatchers.IO) {
                    exportBook(context, bookId, format)
                }
            }

            // Open existing page: notable://page-{uuid}
            path.startsWith("page-") -> {
                val pageId = path.removePrefix("page-")
                logRouter.d("Opening existing page: $pageId")
                navController.navigate("pages/$pageId")
            }

            // Open existing book: notable://book-{uuid}
            path.startsWith("book-") && !path.contains("/") -> {
                val bookId = path.removePrefix("book-")
                logRouter.d("Opening existing book: $bookId")
                navController.navigate("books/$bookId/pages")
            }

            // Create new standalone page: notable://new-page/{uuid}?name=PageName&folder=FolderName
            path.startsWith("new-page/") || path.startsWith("new-page-") -> {
                val pageId = path.removePrefix("new-page/").removePrefix("new-page-")
                val pageName = uri.getQueryParameter("name")
                val folderName = uri.getQueryParameter("folder")
                logRouter.d("Creating new page: $pageId, name: $pageName, folder: $folderName")
                withContext(Dispatchers.IO) {
                    if (folderName != null) {
                        createNewPageInFolderByName(context, folderName, pageId, pageName)
                    } else {
                        createNewPageIfNotExists(context, pageId, pageName)
                    }
                    IndexExporter.exportSync(context)
                }
                navController.navigate("pages/$pageId")
            }

            // Create new page in book: notable://book/{bookId}/new-page/{uuid}?name=PageName
            path.startsWith("book/") && path.contains("/new-page/") -> {
                val parts = path.removePrefix("book/").split("/new-page/")
                if (parts.size == 2) {
                    val bookId = parts[0]
                    val pageId = parts[1]
                    val pageName = uri.getQueryParameter("name")
                    logRouter.d("Creating new page $pageId in book $bookId with name: $pageName")
                    withContext(Dispatchers.IO) {
                        createNewPageInBookIfNotExists(context, bookId, pageId, pageName)
                        IndexExporter.exportSync(context)
                    }
                    navController.navigate("books/$bookId/pages/$pageId")
                }
            }

            // Legacy: Create new page in folder by name (keeping for compatibility)
            path.startsWith("folder/") && path.contains("/new-page/") -> {
                val parts = path.removePrefix("folder/").split("/new-page/")
                if (parts.size == 2) {
                    val folderName = Uri.decode(parts[0])
                    val pageId = parts[1]
                    val pageName = uri.getQueryParameter("name")
                    logRouter.d("Creating new page $pageId in folder '$folderName' with name: $pageName")
                    withContext(Dispatchers.IO) {
                        createNewPageInFolderByName(context, folderName, pageId, pageName)
                        IndexExporter.exportSync(context)
                    }
                    navController.navigate("pages/$pageId")
                }
            }

            else -> {
                logRouter.w("Unknown deep link format: $path")
            }
        }
    } catch (e: Exception) {
        logRouter.e("Error handling deep link: $intentData", e)
    }
}

/**
 * Creates a new standalone page if it doesn't already exist.
 */
private fun createNewPageIfNotExists(context: Context, pageId: String, pageName: String? = null) {
    val repo = AppRepository(context)
    if (repo.pageRepository.getById(pageId) == null) {
        val settings = GlobalAppSettings.current
        val page = Page(
            id = pageId,
            name = pageName,
            background = settings.defaultNativeTemplate
        )
        repo.pageRepository.create(page)
        logRouter.d("Created new standalone page: $pageId with name: $pageName")
    } else {
        logRouter.d("Page already exists: $pageId")
    }
}

/**
 * Creates a new page in a book if it doesn't already exist.
 */
private fun createNewPageInBookIfNotExists(
    context: Context,
    bookId: String,
    pageId: String,
    pageName: String? = null
) {
    val repo = AppRepository(context)
    val book = repo.bookRepository.getById(bookId)

    if (book == null) {
        logRouter.w("Book not found: $bookId")
        return
    }

    if (repo.pageRepository.getById(pageId) == null) {
        val page = book.newPage().copy(id = pageId, name = pageName)
        repo.pageRepository.create(page)
        repo.bookRepository.addPage(bookId, pageId)
        logRouter.d("Created new page $pageId in book $bookId with name: $pageName")
    } else {
        logRouter.d("Page already exists: $pageId")
        // If page exists but not in book, add it
        if (!book.pageIds.contains(pageId)) {
            repo.bookRepository.addPage(bookId, pageId)
            logRouter.d("Added existing page $pageId to book $bookId")
        }
    }
}

/**
 * Creates a new page in a folder (looked up by name) if it doesn't already exist.
 * If no folder with the given name exists, creates the page in the root.
 */
private fun createNewPageInFolderByName(
    context: Context,
    folderName: String,
    pageId: String,
    pageName: String? = null
) {
    val repo = AppRepository(context)
    val folder = repo.folderRepository.getByTitle(folderName)

    if (folder == null) {
        logRouter.w("Folder not found: '$folderName', creating page in root")
    }

    if (repo.pageRepository.getById(pageId) == null) {
        val settings = GlobalAppSettings.current
        val page = Page(
            id = pageId,
            name = pageName,
            parentFolderId = folder?.id,
            background = settings.defaultNativeTemplate
        )
        repo.pageRepository.create(page)
        logRouter.d("Created new page $pageId in folder '${folderName}' (${folder?.id}) with name: $pageName")
    } else {
        logRouter.d("Page already exists: $pageId")
    }
}

/**
 * Creates a new folder.
 */
private fun createNewFolder(context: Context, folderName: String, parentFolderName: String?) {
    val repo = AppRepository(context)
    val parentFolder = parentFolderName?.let { repo.folderRepository.getByTitle(it) }

    val folder = Folder(
        title = folderName,
        parentFolderId = parentFolder?.id
    )
    repo.folderRepository.create(folder)
    logRouter.d("Created new folder: $folderName in parent: ${parentFolder?.title ?: "root"}")
}

/**
 * Creates a new book/notebook in a folder.
 * Returns the book ID if created successfully.
 */
private fun createNewBook(context: Context, bookName: String, folderName: String?): String? {
    val repo = AppRepository(context)
    val folder = folderName?.let { repo.folderRepository.getByTitle(it) }

    val notebook = Notebook(
        title = bookName,
        parentFolderId = folder?.id
    )
    repo.bookRepository.create(notebook)
    logRouter.d("Created new book: $bookName in folder: ${folder?.title ?: "root"}")
    return notebook.id
}

/**
 * Exports a page to the specified format.
 */
private suspend fun exportPage(context: Context, pageId: String, format: String) {
    val exportFormat = when (format.lowercase()) {
        "pdf" -> ExportFormat.PDF
        "png" -> ExportFormat.PNG
        "jpg", "jpeg" -> ExportFormat.JPEG
        "xopp" -> ExportFormat.XOPP
        else -> {
            logRouter.w("Unknown export format: $format, defaulting to PNG")
            ExportFormat.PNG
        }
    }

    val result = ExportEngine(context).export(
        target = ExportTarget.Page(pageId = pageId),
        format = exportFormat
    )
    logRouter.d("Export result: $result")
}

/**
 * Exports a book to the specified format.
 */
private suspend fun exportBook(context: Context, bookId: String, format: String) {
    val exportFormat = when (format.lowercase()) {
        "pdf" -> ExportFormat.PDF
        "png" -> ExportFormat.PNG
        "xopp" -> ExportFormat.XOPP
        else -> {
            logRouter.w("Unknown export format: $format, defaulting to PDF")
            ExportFormat.PDF
        }
    }

    val result = ExportEngine(context).export(
        target = ExportTarget.Book(bookId = bookId),
        format = exportFormat
    )
    logRouter.d("Export result: $result")
}