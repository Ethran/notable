package com.ethran.notable.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.EditorView
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.ui.components.QuickNav
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.BugReportScreen
import com.ethran.notable.ui.views.Library
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.PagesDestination
import com.ethran.notable.ui.views.PagesView
import com.ethran.notable.ui.views.SettingsDestination
import com.ethran.notable.ui.views.SettingsView
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.SystemInformationView
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.ui.views.WelcomeView
import io.shipbook.shipbooksdk.ShipBook

private val logRouter = ShipBook.getLogger("Router")

@Composable
@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalComposeUiApi
fun NotableNavHost(
    modifier: Modifier = Modifier,
    appNavState: NotableAppState
) {


    val appRepository = AppRepository(LocalContext.current)


    fun goToPage(pageId: String) {
        val logPagesRow = ShipBook.getLogger("QuickNav")

        // Navigate to selected page
        val bookId = runCatching {
            appRepository.pageRepository.getById(pageId)?.notebookId
        }.onFailure {
            logPagesRow.d(
                "failed to resolve bookId for $pageId",
                it
            )
        }.getOrNull()
        val url = EditorDestination.createRoute(pageId, bookId)
        logPagesRow.d("navigate -> $url")
        appNavState.navController.navigate(url)
    }

    fun onCreateNewQuickPage(folderId: String?) {
        val pageId =
            appRepository.createNewQuickPage(parentFolderId = folderId)
                ?: return
        appNavState.navController.navigate(EditorDestination.createRoute(pageId, null))
    }

    NavHost(
        modifier = modifier,
        navController = appNavState.navController,
        startDestination = appNavState.startDestination,

        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(
            route = LibraryDestination.routeWithArgs,
            arguments = listOf(navArgument("folderId") { nullable = true }),
        ) {
            Library(
                navController = appNavState.navController,
                folderId = it.arguments?.getString("folderId"),
                goToPage = ::goToPage,
                onCreateNewQuickPage = ::onCreateNewQuickPage
            )
            appNavState.currentPageId = null
        }
        composable(
            route = WelcomeDestination.route,
        ) {
            WelcomeView(
                onContinue = {
                    // TODO: move the logic
                    appRepository.kvProxy.setAppSettings(
                        GlobalAppSettings.current.copy(showWelcome = false)
                    )
                    appNavState.navController.navigate(LibraryDestination.route)

                },
            )
            appNavState.currentPageId = null
        }
        composable(
            route = SystemInformationDestination.route,
        ) {
            SystemInformationView(
                onBack = { appNavState.navController.popBackStack() },
            )
            appNavState.currentPageId = null
        }

        composable(
            route = EditorDestination.routeWithArgs,
            arguments = listOf(
                navArgument(EditorDestination.PAGE_ID_ARG) { type = NavType.StringType },
                navArgument(EditorDestination.BOOK_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString(EditorDestination.BOOK_ID_ARG)

            // Priority: SavedStateHandle (for process death/recomposition) > Nav Argument
            val currentPageId =
                backStackEntry.savedStateHandle.get<String>(EditorDestination.PAGE_ID_ARG)
                    ?: backStackEntry.arguments?.getString(EditorDestination.PAGE_ID_ARG)!!

            // Sync state
            appNavState.currentPageId = currentPageId
            backStackEntry.savedStateHandle[EditorDestination.PAGE_ID_ARG] = currentPageId

            EditorView(
                navController = appNavState.navController,
                bookId = bookId,
                pageId = currentPageId,
                onPageChange = { newPageId ->
                    // SAVE new pageId in savedStateHandle - do not call navigate
                    backStackEntry.savedStateHandle["pageId"] = newPageId
                    if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 2) {
                        backStackEntry.savedStateHandle["pageChangesSinceJump"] = 1
                    } else if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 1) {
                        backStackEntry.savedStateHandle.remove<Int>("pageChangesSinceJump")
                        backStackEntry.savedStateHandle.remove<String>("quickNavSourcePageId")
                    }
                    appNavState.currentPageId = newPageId
                    logRouter.d("Editor changed page -> saved pageId=$newPageId (no navigate, no recreate)")

                }
            )
        }
        composable(
            route = PagesDestination.routeWithArgs,
            arguments = listOf(navArgument("bookId") {
                /* configuring arguments for navigation */
                type = NavType.StringType
            }),
        ) {
            PagesView(
                navController = appNavState.navController,
                bookId = it.arguments?.getString("bookId")!!,
            )
        }
        composable(
            route = SettingsDestination.route,
        ) {
            SettingsView(
                onBack = { appNavState.navController.popBackStack() },
                onNavigateToWelcome = { appNavState.navController.navigate(WelcomeDestination.route) },
            )
            appNavState.currentPageId = null
        }
        composable(
            route = BugReportDestination.route,
        ) {
            BugReportScreen(navController = appNavState.navController)
            appNavState.currentPageId = null
        }
    }
    val quickNavSourcePageId =
        appNavState.navController.currentBackStackEntry?.savedStateHandle?.get<String>("quickNavSourcePageId")
    if (appNavState.isQuickNavOpen) {
        QuickNav(
            currentPageId = appNavState.currentPageId,
            quickNavSourcePageId = quickNavSourcePageId,
            onClose = {
                appNavState.isQuickNavOpen = false
                if (quickNavSourcePageId == appNavState.currentPageId)
                // User didn't use the QuickNav, so remove the savedStateHandle
                    appNavState.navController.currentBackStackEntry?.savedStateHandle?.remove<String>(
                        "quickNavSourcePageId"
                    )
                else
                // user did change page with QuickNav, start counting page changes
                    appNavState.navController.currentBackStackEntry?.savedStateHandle?.set(
                        "pageChangesSinceJump",
                        2
                    )

                refreshScreen()
            },
            goToPage = {
            },
            goToFolder = {
                appNavState.navController.navigate("library" + if (it == null) "" else "?folderId=$it")
            }
        )
    }

}