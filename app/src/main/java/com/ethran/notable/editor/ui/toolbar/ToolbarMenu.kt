package com.ethran.notable.editor.ui.toolbar

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
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.io.XoppFile
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.noRippleClickable
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
    val appRepository = AppRepository(context)
    val page = appRepository.pageRepository.getById(state.pageId)!!
    val book =
        if (page.notebookId != null) appRepository.bookRepository.getById(page.notebookId) else null
    val parentFolder =
        if (book != null) book.parentFolderId
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
                                    ExportEngine(context).export(
                                        target = ExportTarget.Page(pageId = state.pageId),
                                        format = ExportFormat.PDF,
                                    )
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
                                        ExportEngine(context).export(
                                            target = ExportTarget.Page(pageId = state.pageId),
                                            format = ExportFormat.PNG,
                                        )
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
                            val message =
                                snackManager.showSnackDuring(text = "Exporting the page to JPEG...") {
                                    delay(10L)

                                    ExportEngine(context).export(
                                        target = ExportTarget.Page(pageId = state.pageId),
                                        format = ExportFormat.JPEG,
                                    )
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
                                XoppFile(context).exportPage(state.pageId)
                            }
                        }
                        onClose()
                    }
            ) { Text("Export page to xopp") }

            if (state.bookId != null){
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                val message =
                                    snackManager.showSnackDuring("Exporting the book to PDF...") {
                                        ExportEngine(context).export(
                                            target = ExportTarget.Book(bookId =  state.bookId),
                                            format = ExportFormat.PDF,
                                        )
                                    }
                                snackManager.displaySnack(
                                    SnackConf(text = message, duration = 2000)
                                )
                            }
                            onClose()
                        }
                ) { Text("Export book to PDF") }
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                val message = snackManager.showSnackDuring(
                                    text = "Exporting the book to PNG..."
                                ) {
                                    ExportEngine(context).export(
                                        target = ExportTarget.Book(bookId = state.bookId),
                                        format = ExportFormat.JPEG,
                                    )
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
                                    XoppFile(context).exportBook(state.bookId)
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
        }
    }
}
