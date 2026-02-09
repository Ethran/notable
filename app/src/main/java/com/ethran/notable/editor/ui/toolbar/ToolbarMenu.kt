package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.CanvasEventBus
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.noRippleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ToolbarMenu(
    navController: NavController,
    state: EditorState,
    onClose: () -> Unit,
    onBackgroundSelectorModalOpen: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current
    val appRepository = remember { AppRepository(context) }
    val page = appRepository.pageRepository.getById(state.currentPageId)!!
    val book =
        if (page.notebookId != null) appRepository.bookRepository.getById(page.notebookId) else null
    val parentFolder = if (book != null) book.parentFolderId
    else page.parentFolderId

    val exportingPageToPdfMsg = stringResource(R.string.exporting_the_page_to, "PDF")
    val exportingPageToPngMsg = stringResource(R.string.exporting_the_page_to, "PNG")
    val exportingPageToJpegMsg = stringResource(R.string.exporting_the_page_to, "JPEG")
    val exportingPageToXoppMsg = stringResource(R.string.exporting_the_page_to, "xopp")
    val exportingBookToPdfMsg = stringResource(R.string.exporting_the_book_to, "PDF")
    val exportingBookToPngMsg = stringResource(R.string.exporting_the_book_to, "PNG")
    val exportingBookToXoppMsg = stringResource(R.string.exporting_the_book_to, "xopp")
    val clearedAllStrokesMsg = stringResource(R.string.cleared_all_strokes)

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = { onClose() },
        offset = IntOffset(
            convertDpToPixel((-10).dp, context).toInt(), convertDpToPixel(50.dp, context).toInt()
        ),
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(bottom = (BUTTON_SIZE + 5).dp) // For toolbar is located at the button,
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max)
        ) {
            // Library
            MenuItem(stringResource(R.string.home_view_name)) {
                navController.navigate(
                    if (parentFolder != null) "library?folderId=$parentFolder" else "library"
                )
                onClose()
            }
            DividerCentered()

            // Page exports
            MenuItem(stringResource(R.string.export_page_to, "PDF")) {
                scope.launch(Dispatchers.IO) {
                    snackManager.runWithSnack(exportingPageToPdfMsg) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.PDF
                        )
                    }
                }
                onClose()
            }
            MenuItem(stringResource(R.string.export_page_to, "PNG")) {
                scope.launch(Dispatchers.IO) {
                    snackManager.runWithSnack(exportingPageToPngMsg) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.PNG
                        )
                    }
                }
                onClose()
            }
            MenuItem(stringResource(R.string.export_page_to, "JPEG")) {
                scope.launch(Dispatchers.IO) {
                    snackManager.runWithSnack(exportingPageToJpegMsg) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.JPEG
                        )
                    }
                }
                onClose()
            }
            MenuItem(stringResource(R.string.export_page_to, "xopp")) {
                scope.launch(Dispatchers.IO) {
                    snackManager.runWithSnack(exportingPageToXoppMsg) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.XOPP
                        )
                    }
                }
                onClose()
            }
            DividerCentered()

            // Book exports
            if (state.bookId != null && book != null) {
                MenuItem(stringResource(R.string.export_book_to, "PDF")) {
                    scope.launch(Dispatchers.IO) {
                        snackManager.runWithSnack(exportingBookToPdfMsg) {
                            ExportEngine(context).export(
                                target = ExportTarget.Book(bookId = state.bookId),
                                format = ExportFormat.PDF
                            )
                        }
                    }
                    onClose()
                }
                MenuItem(stringResource(R.string.export_book_to, "PNG")) {
                    scope.launch(Dispatchers.IO) {
                        snackManager.runWithSnack(exportingBookToPngMsg) {
                            ExportEngine(context).export(
                                target = ExportTarget.Book(bookId = state.bookId),
                                format = ExportFormat.PNG
                            )
                        }
                    }
                    onClose()
                }
                MenuItem(stringResource(R.string.export_book_to, "xopp")) {
                    scope.launch(Dispatchers.IO) {
                        snackManager.runWithSnack(exportingBookToXoppMsg) {
                            ExportEngine(context).export(
                                target = ExportTarget.Book(bookId = state.bookId),
                                format = ExportFormat.XOPP
                            )
                        }
                    }
                    onClose()
                }
                DividerCentered()
            }

            MenuItem(stringResource(R.string.clean_all_strokes)) {
                scope.launch {
                    CanvasEventBus.clearPageSignal.emit(Unit)
                    snackManager.displaySnack(
                        SnackConf(
                            text = clearedAllStrokesMsg, duration = 3000
                        )
                    )
                }
                onClose()
            }
            DividerCentered()

            MenuItem(stringResource(R.string.change_background)) {
                onBackgroundSelectorModalOpen()
                onClose()
            }

            MenuItem(stringResource(R.string.bug_report)) {
                navController.navigate("bugReport")
                onClose()
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String, onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()                                 // occupy the menu's width
            .noRippleClickable { onClick() }                // click covers entire box
            .padding(horizontal = 10.dp, vertical = 8.dp)   // inner spacing
    ) {
        Text(
            text = label,
            color = Color.Black
        )
    }
}

@Composable
private fun ColumnScope.DividerCentered() {
    Box(
        Modifier
            .fillMaxWidth(1f / 2f)
            .align(Alignment.CenterHorizontally)
            .height(0.5.dp)
            .background(Color(0xFF777777))
    )
}