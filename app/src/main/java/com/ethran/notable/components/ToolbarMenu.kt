package com.ethran.notable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.NavController
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.XoppFile
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.convertDpToPixel
import com.ethran.notable.utils.copyPagePngLinkForObsidian
import com.ethran.notable.utils.exportBook
import com.ethran.notable.utils.exportBookToPng
import com.ethran.notable.utils.exportPage
import com.ethran.notable.utils.exportPageToJpeg
import com.ethran.notable.utils.exportPageToPng
import com.ethran.notable.utils.noRippleClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToolbarMenu(
    navController: NavController,
    state: EditorState,
    onClose: () -> Unit,
    onBackgroundSelectorModalOpen: () -> Unit
) {
    val context = LocalContext.current
    val scope = CoroutineScope(Dispatchers.IO)
    val snackManager = LocalSnackContext.current
    val page = AppRepository(context).pageRepository.getById(state.pageId)!!
    val parentFolder =
        if (page.notebookId != null)
            AppRepository(context).bookRepository.getById(page.notebookId)!!
                .parentFolderId
        else page.parentFolderId

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = { onClose() },
        offset =
            IntOffset(
                convertDpToPixel((-10).dp, context).toInt(),
                convertDpToPixel(50.dp, context).toInt()
            ),
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .noRippleClickable {
                        navController.navigate(
                            route = if (parentFolder != null) "library?folderId=${parentFolder}"
                            else "library"
                        )
                    }
            ) { Text("Library") }
            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            val message =
                                snackManager.showSnackDuring("Exporting the page to PDF...") {
                                    delay(10L)
                                    // Q:  Why do I need this ?
                                    // A: I guess that we need to wait for strokes to be drawn.
                                    // checking if drawingInProgress.isLocked should be enough
                                    // but I do not have time to test it.
                                    withContext(Dispatchers.IO) {
                                        exportPage(context, state.pageId)
                                    }
                                }
                            snackManager.displaySnack(
                                SnackConf(text = message, duration = 2000)
                            )
                        }
                        onClose()
                    }
            ) { Text("Export page to PDF") }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            val message =
                                snackManager.showSnackDuring(text = "Exporting the page to PNG...") {
                                    delay(10L)
                                    withContext(Dispatchers.IO) {
                                        exportPageToPng(context, state.pageId)
                                    }
                                }
                            snackManager.displaySnack(
                                SnackConf(text = message, duration = 2000)
                            )
                        }
                        onClose()
                    }
            ) { Text("Export page to PNG") }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            delay(10L)
                            copyPagePngLinkForObsidian(context, state.pageId)
                            snackManager.displaySnack(
                                SnackConf(text = "Copied page link for obsidian", duration = 2000)
                            )
                        }
                        onClose()
                    }
            ) { Text("Copy page png link for obsidian") }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            val message =
                                snackManager.showSnackDuring(text = "Exporting the page to JPEG...") {
                                    delay(10L)

                                    withContext(Dispatchers.IO) {
                                        exportPageToJpeg(context, state.pageId)
                                    }
                                }
                            snackManager.displaySnack(
                                SnackConf(text = message, duration = 2000)
                            )
                        }
                        onClose()
                    }
            ) { Text("Export page to JPEG") }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            snackManager.showSnackDuring(
                                text = "Exporting the page to xopp"
                            ) {
                                delay(10L)
                                XoppFile.exportPage(context, state.pageId)
                            }
                        }
                        onClose()
                    }
            ) { Text("Export page to xopp") }

            if (state.bookId != null)
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                val message =
                                    snackManager.showSnackDuring("Exporting the book to PDF...") {
                                        delay(10L)
                                        withContext(Dispatchers.IO) {
                                            exportBook(context, state.bookId)
                                        }
                                    }
                                snackManager.displaySnack(
                                    SnackConf(text = message, duration = 2000)
                                )
                            }
                            onClose()
                        }
                ) { Text("Export book to PDF") }

            if (state.bookId != null) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                val message = snackManager.showSnackDuring(
                                    text = "Exporting the book to PNG..."
                                ) {
                                    delay(10L)
                                    withContext(Dispatchers.IO) {
                                        exportBookToPng(context, state.bookId)
                                    }
                                }
                                snackManager.displaySnack(
                                    SnackConf(text = message, duration = 2000)
                                )
                            }
                            onClose()
                        }
                ) { Text("Export book to PNG") }
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                snackManager.showSnackDuring(
                                    text = "Exporting the book to xopp"
                                ) {
                                    delay(10L)
                                    XoppFile.exportBook(context, state.bookId)
                                }

                            }
                            onClose()
                        }
                ) { Text("Export book to xopp") }
            }
            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        navController.navigate("bugReport") {}
                    }
            ) { Text("Bug Report") }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.Black)
            )
            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        onBackgroundSelectorModalOpen()
                        onClose()
                    }
            ) { Text("Change Background") }

            /*Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.Black)
            )
            Box(Modifier.padding(10.dp)) {
                Text("Refresh page")
            }*/
        }
    }
}
