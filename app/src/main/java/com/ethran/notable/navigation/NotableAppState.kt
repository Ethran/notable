package com.ethran.notable.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.ui.views.hasFilePermission
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("NotableAppState")

@Composable
fun rememberNotableAppState(
    navController: NavHostController = rememberNavController()
): NotableAppState {
    val context = LocalContext.current
    return remember(navController, context) {
        NotableAppState(navController, hasFilePermission(context))
    }
}

@Stable
class NotableAppState(
    val navController: NavHostController,
    private val hasFilePermission: Boolean
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
    }

    fun closeQuickNav() {
        isQuickNavOpen = false
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
    suspend fun updateDrawingState() {
        log.d("Changing drawing state, isQuickNavOpen: $isQuickNavOpen")
        CanvasEventBus.isDrawing.emit(!isQuickNavOpen)
    }
}