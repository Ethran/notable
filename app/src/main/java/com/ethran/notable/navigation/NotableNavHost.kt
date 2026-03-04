package com.ethran.notable.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.EditorView
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
fun NotableNavHost(
    modifier: Modifier = Modifier,
    appNavState: NotableAppState
) {
    val appRepository = AppRepository(LocalContext.current)

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = appNavState.navController,
            startDestination = appNavState.startDestination,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(
                route = LibraryDestination.routeWithArgs,
                arguments = listOf(navArgument(LibraryDestination.FOLDER_ID_ARG) {
                    nullable = true
                }),
            ) {
                Library(
                    navController = appNavState.navController,
                    folderId = it.arguments?.getString(LibraryDestination.FOLDER_ID_ARG),
                    goToPage = { pageId -> appNavState.goToPage(appRepository, pageId) },
                    onCreateNewQuickPage = { folderId ->
                        appNavState.onCreateNewQuickPage(
                            appRepository,
                            folderId
                        )
                    }
                )
                appNavState.currentPageId = null
            }
            composable(
                route = WelcomeDestination.route,
            ) {
                WelcomeView(
                    goToLibrary = { appNavState.goToLibrary(null) },
                )
                appNavState.currentPageId = null
            }
            composable(
                route = SystemInformationDestination.route,
            ) {
                SystemInformationView(
                    onBack = { appNavState.goBack() },
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
                arguments = listOf(navArgument(PagesDestination.BOOK_ID_ARG) {
                    /* configuring arguments for navigation */
                    type = NavType.StringType
                }),
            ) {
                PagesView(
                    goToLibrary = { appNavState.goToLibrary(it) },
                    goToEditor = { pageId, bId -> appNavState.goToEditor(pageId, bId) },
                    bookId = it.arguments?.getString(PagesDestination.BOOK_ID_ARG)!!,
                )
            }
            composable(
                route = SettingsDestination.route,
            ) {
                SettingsView(
                    onBack = { appNavState.goBack() },
                    goToWelcome = { appNavState.goToWelcome() },
                    goToSystemInfo = { appNavState.goToSystemInfo() }
                )
                appNavState.currentPageId = null
            }
            composable(
                route = BugReportDestination.route,
            ) {
                BugReportScreen(goBack = { appNavState.goBack() })
                appNavState.currentPageId = null
            }
        }
    }

}