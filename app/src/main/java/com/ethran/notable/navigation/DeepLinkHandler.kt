package com.ethran.notable.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.ethran.notable.navigation.DeepLinkResolver.Target
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

private val log = ShipBook.getLogger("DeepLinkHandler")

/** Opens each of [requests], asking before a linked PDF is imported and how. */
@Composable
fun DeepLinkHandler(
    requests: Flow<LinkRequests.Request>,
    resolver: DeepLinkResolver,
    navigator: NotableNavigator,
    snackDispatcher: SnackDispatcher,
) {
    val scope = rememberCoroutineScope()
    var askToImport by remember { mutableStateOf<DeepLink.Pdf?>(null) }

    fun snack(text: String) = snackDispatcher.showOrUpdateSnack(SnackConf(text = text, duration = 3000))

    fun go(target: Target) {
        log.d("Opening $target")
        when (target) {
            is Target.Editor -> {
                navigator.showPage(target.pageId, target.bookId)
                target.note?.let(::snack)
            }

            is Target.Pages -> navigator.goToPages(target.bookId)
            is Target.AskToImport -> askToImport = target.link
            is Target.Failed -> snack(target.message)
        }
    }

    LaunchedEffect(requests) {
        requests.collect { request ->
            if (navigator.isOnWelcome) {
                log.i("Ignoring $request until setup is finished")
                snack("Finish setting up Notable first")
            } else when (request) {
                is LinkRequests.Request.Open -> go(resolver.resolve(request.link))
                is LinkRequests.Request.Show -> go(request.target)
            }
        }
    }

    askToImport?.let { link ->
        fun import(followFile: Boolean) {
            askToImport = null
            scope.launch {
                snack("Importing ${File(link.path).name}…")
                go(resolver.import(link, followFile))
            }
        }
        PdfImportChoiceDialog(
            uri = Uri.fromFile(File(link.path)),
            onCopy = { import(followFile = false) },
            onObserve = { import(followFile = true) },
            onDismiss = { askToImport = null },
        )
    }
}
