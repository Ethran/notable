package com.ethran.notable.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.navigation.DeepLinkResolver.PdfPreview
import com.ethran.notable.navigation.DeepLinkResolver.Target
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.dialogs.ShowConfirmationDialog
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

private val log = ShipBook.getLogger("DeepLinkHandler")

/** Opens each link from [links], asking before a PDF is imported. */
@Composable
fun DeepLinkHandler(
    links: Flow<DeepLink>,
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

    LaunchedEffect(links) {
        links.collect { link ->
            if (navigator.isOnWelcome) {
                log.i("Ignoring $link until setup is finished")
                snack("Finish setting up Notable first")
            } else go(resolver.resolve(link))
        }
    }

    askToImport?.let { link ->
        val preview by produceState<PdfPreview?>(null, link) { value = resolver.preview(link) }
        ShowConfirmationDialog(
            title = "Import PDF",
            content = {
                Row {
                    Box(
                        modifier = Modifier.size(200.dp, 250.dp).background(Color.Gray),
                        contentAlignment = Alignment.Center,
                    ) {
                        preview?.firstPage?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "First page",
                                // The page's own proportions, as large as the box allows. The
                                // rendered page is transparent where nothing is drawn.
                                modifier = Modifier
                                    .aspectRatio(it.width.toFloat() / it.height)
                                    .background(Color.White)
                                    .border(1.dp, Color.Black),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.width(320.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(File(link.path).name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(link.path, fontSize = 14.sp)
                        preview?.let {
                            Text(if (it.pageCount == 1) "1 page" else "${it.pageCount} pages", fontSize = 16.sp)
                        }
                        Text("Notable will observe this file and show its changes.", fontSize = 16.sp)
                    }
                }
            },
            onConfirm = {
                askToImport = null
                scope.launch {
                    snack("Importing ${File(link.path).name}…")
                    go(resolver.import(link))
                }
            },
            onCancel = { askToImport = null },
            confirmButtonText = "Import",
        )
    }
}
