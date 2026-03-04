package com.ethran.notable.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.utils.hasFilePermission
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("NotableAppState")

@Composable
fun rememberNotableAppState(
    navController: NavHostController = rememberNavController(),
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): NotableNavigator {
    val context = LocalContext.current
    return remember(navController, context, coroutineScope) {
        NotableNavigator(navController, hasFilePermission(context), coroutineScope)
    }
}

@Stable
class NotableNavigator(
    val navController: NavHostController,
    private val hasFilePermission: Boolean,
    private val coroutineScope: CoroutineScope
) {
    var isQuickNavOpen by mutableStateOf(false)
    var currentPageId by mutableStateOf<String?>(null)


    val startDestination: String
        get() = if (GlobalAppSettings.current.showWelcome || !hasFilePermission) WelcomeDestination.route
        else LibraryDestination.route

    val quickNavSourcePageId: String?
        get() = navController.currentBackStackEntry?.savedStateHandle?.get<String>("quickNavSourcePageId")

    fun openQuickNav() {
        navController.currentBackStackEntry?.savedStateHandle?.set(
            "quickNavSourcePageId", currentPageId
        )
        isQuickNavOpen = true
        updateDrawingState()
    }

    fun closeQuickNav() {
        isQuickNavOpen = false
        updateDrawingState()
        if (quickNavSourcePageId == currentPageId) {
            // User didn't use the QuickNav, so remove the savedStateHandle
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("quickNavSourcePageId")
        } else {
            // user did change page with QuickNav, start counting page changes
            navController.currentBackStackEntry?.savedStateHandle?.set("pageChangesSinceJump", 2)
        }
        refreshScreen()
    }

    fun goToAnchor(appRepository: AppRepository){
        val notebookId = runCatching {
            appRepository.pageRepository.getById(quickNavSourcePageId?: return)?.notebookId
        }.onFailure {
            log.w("Failed to load page $quickNavSourcePageId", it)
        }.getOrNull()
        navController.navigate(EditorDestination.createRoute(quickNavSourcePageId!!, notebookId))

    }

    fun shouldAnchorBeVisible(): Boolean {
        return isQuickNavOpen && quickNavSourcePageId != currentPageId
    }

    // Updates the drawing state based on whether QuickNav is open
    fun updateDrawingState() {
        coroutineScope.launch {
            log.d("Changing drawing state, isQuickNavOpen: $isQuickNavOpen")
            CanvasEventBus.isDrawing.emit(!isQuickNavOpen)
        }
    }

    fun goToLibrary(folderId: String?) {
        navController.navigate(LibraryDestination.createRoute(folderId))
    }

    fun goToEditor(pageId: String, bookId: String?) {
        navController.navigate(EditorDestination.createRoute(pageId, bookId))
    }

    fun goBack() {
        navController.popBackStack()
    }

    fun goToWelcome() {
        navController.navigate(WelcomeDestination.route)
    }

    fun goToSystemInfo() {
        navController.navigate(SystemInformationDestination.route)
    }

    fun goToPage(appRepository: AppRepository, pageId: String) {
        val bookId = runCatching {
            appRepository.pageRepository.getById(pageId)?.notebookId
        }.onFailure {
            log.d("failed to resolve bookId for $pageId", it)
        }.getOrNull()
        val url = EditorDestination.createRoute(pageId, bookId)
        log.d("navigate -> $url")
        navController.navigate(url)
    }

    fun onCreateNewQuickPage(appRepository: AppRepository, folderId: String?) {
        val pageId = appRepository.createNewQuickPage(parentFolderId = folderId) ?: return
        navController.navigate(EditorDestination.createRoute(pageId, null))
    }


    fun onPageChange(backStackEntry: NavBackStackEntry, newPageId: String) {
        // SAVE new pageId in savedStateHandle - do not call navigate
        backStackEntry.savedStateHandle["pageId"] = newPageId
        if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 2) {
            backStackEntry.savedStateHandle["pageChangesSinceJump"] = 1
        } else if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 1) {
            backStackEntry.savedStateHandle.remove<Int>("pageChangesSinceJump")
            backStackEntry.savedStateHandle.remove<String>("quickNavSourcePageId")
        }
        currentPageId = newPageId
        log.d("Editor changed page -> saved pageId=$newPageId (no navigate, no recreate)")
    }

    /**
     * Resolves the pageId from the backStackEntry (prioritizing saved state),
     * synchronizes the internal state, and ensures the SavedStateHandle is updated.
     */
    fun resolveAndSyncPageId(backStackEntry: NavBackStackEntry): String {

        // Priority: SavedStateHandle (for process death/recomposition) > Nav Argument
        val newCurrentPageId =
            backStackEntry.savedStateHandle.get<String>(EditorDestination.PAGE_ID_ARG)
                ?: backStackEntry.arguments?.getString(EditorDestination.PAGE_ID_ARG)!!

        // Sync state
        currentPageId = newCurrentPageId
        backStackEntry.savedStateHandle[EditorDestination.PAGE_ID_ARG] = currentPageId
        return newCurrentPageId
    }

    fun cleanCurrentPageId() {
        currentPageId = null
    }
}
