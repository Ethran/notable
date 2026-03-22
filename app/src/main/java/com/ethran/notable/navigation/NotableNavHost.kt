package com.ethran.notable.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.EditorView
import com.ethran.notable.io.ExportEngine
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

private val log = ShipBook.getLogger("NotableNavHost")

@Composable
fun NotableNavHost(
    exportEngine: ExportEngine,
    appRepository: AppRepository,
    modifier: Modifier = Modifier,
    appNavigator: NotableNavigator
) {

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = appNavigator.navController,
            startDestination = appNavigator.startDestination,
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
                    navController = appNavigator.navController,
                    folderId = it.arguments?.getString(LibraryDestination.FOLDER_ID_ARG),
                    goToPage = { pageId -> appNavigator.goToPage(appRepository, pageId) },
                    onCreateNewQuickPage = { folderId ->
                        appNavigator.onCreateNewQuickPage(
                            appRepository,
                            folderId
                        )
                    }
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = WelcomeDestination.route,
            ) {
                WelcomeView(
                    goToLibrary = { appNavigator.goToLibrary(null) },
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = SystemInformationDestination.route,
            ) {
                SystemInformationView(
                    onBack = { appNavigator.goBack() },
                )
                appNavigator.cleanCurrentPageId()
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

                val currentPageId = appNavigator.resolveAndSyncPageId(backStackEntry)

                EditorView(
                    goToLibrary = {appNavigator.goToLibrary(it)},
                    goToPages = { bookId -> appNavigator.goToPages(bookId) },
                    goToBugReport = { appNavigator.goToBugReport() },
                    bookId = bookId,
                    initialPageId = currentPageId,
                    isQuickNavOpen = appNavigator.isQuickNavOpen,
                    onPageChange = { newPageId ->
                        log.d("onPageChange: $newPageId")
                        appNavigator.onPageChange(
                            backStackEntry,
                            newPageId
                        )
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
                    goToLibrary = { folderId -> appNavigator.goToLibrary(folderId) },
                    goToEditor = { pageId, bId -> appNavigator.goToEditor(pageId, bId) },
                    bookId = it.arguments?.getString(PagesDestination.BOOK_ID_ARG)!!,
                )
            }
            composable(
                route = SettingsDestination.route,
            ) {
                SettingsView(
                    onBack = { appNavigator.goBack() },
                    goToWelcome = { appNavigator.goToWelcome() },
                    goToSystemInfo = { appNavigator.goToSystemInfo() }
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = BugReportDestination.route,
            ) {
                BugReportScreen(goBack = { appNavigator.goBack() })
                appNavigator.cleanCurrentPageId()
            }
        }
    }
}