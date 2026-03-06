package com.ethran.notable.ui.components

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.rememberNotableAppState
import com.ethran.notable.ui.SnackBar
import com.ethran.notable.ui.SnackState

@OptIn(
    ExperimentalAnimationApi::class,
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun NotableApp(snackState: SnackState) {
    val appNavState = rememberNotableAppState()
    val context = LocalContext.current
    val appRepository = remember { AppRepository(context) }
    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickNav() }
    ) {
        NotableNavHost(Modifier, appNavState)


        // overlays
        if (appNavState.isQuickNavOpen) {
            QuickNav(
                currentPageId = appNavState.currentPageId,
                quickNavSourcePageId = appNavState.quickNavSourcePageId,
                onClose = { appNavState.closeQuickNav() },
                goToPage = { pageId -> appNavState.goToPage(appRepository, pageId) },
                goToFolder = { folderId -> appNavState.goToLibrary(folderId) }
            )
        }

        if (appNavState.shouldAnchorBeVisible()) {
            Anchor(
                onClose = {
                    appNavState.goToAnchor(appRepository)
                    appNavState.closeQuickNav()
                }
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black)
    )
    SnackBar(state = snackState)
}