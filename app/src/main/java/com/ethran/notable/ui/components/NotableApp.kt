package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.rememberNotableAppState
import com.ethran.notable.ui.SnackBar
import com.ethran.notable.ui.SnackState


@Composable
fun NotableApp(
    exportEngine: ExportEngine,
    snackState: SnackState,
    appRepository: AppRepository
) {
    val appNavState = rememberNotableAppState()
    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickNav() }
    ) {
        NotableNavHost(
            exportEngine = exportEngine,
            appRepository = appRepository,
            appNavigator = appNavState
        )


        // overlays
        if (appNavState.isQuickNavOpen) {
            QuickNav(
                appRepository = appRepository,
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