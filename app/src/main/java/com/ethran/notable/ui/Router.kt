package com.ethran.notable.ui

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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.EditorView
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.ui.components.Anchor
import com.ethran.notable.ui.components.QuickNav
import com.ethran.notable.ui.views.BugReportScreen
import com.ethran.notable.ui.views.Library
import com.ethran.notable.ui.views.PagesView
import com.ethran.notable.ui.views.SettingsView
import com.ethran.notable.ui.views.WelcomeView
import com.ethran.notable.ui.views.hasFilePermission
import io.shipbook.shipbooksdk.ShipBook
import kotlin.coroutines.cancellation.CancellationException


private val logRouter = ShipBook.getLogger("Router")

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@Composable
fun Router() {
    val navController = rememberNavController()
    var isQuickNavOpen by remember {
        mutableStateOf(false)
    }
    var currentPageId: String? by remember { mutableStateOf(null) }

    LaunchedEffect(isQuickNavOpen) {
        logRouter.d("Changing drawing state, isQuickNavOpen: $isQuickNavOpen")
        DrawCanvas.isDrawing.emit(!isQuickNavOpen)
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