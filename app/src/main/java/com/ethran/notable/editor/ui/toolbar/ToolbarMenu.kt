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
import com.ethran.notable.editor.DrawCanvas.Companion.clearPageSignal
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.noRippleClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val page = appRepository.pageRepository.getById(state.currentPageId)!!
    val book =
        if (page.notebookId != null) appRepository.bookRepository.getById(page.notebookId) else null
    val parentFolder = if (book != null) book.parentFolderId
    else page.parentFolderId

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
            MenuItem(context.getString(R.string.export_page_to, "PDF")) {
                scope.launch {
                    snackManager.runWithSnack(
                        context.getString(
                            R.string.exporting_the_page_to, "PDF"
                        )
                    ) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.PDF
                        )
                    }
                }
                onClose()
            }
            MenuItem(context.getString(R.string.export_page_to, "PNG")) {
                scope.launch {
                    snackManager.runWithSnack(
                        context.getString(
                            R.string.exporting_the_page_to, "PNG"
                        )
                    ) {
                        withContext(Dispatchers.IO) {
                            ExportEngine(context).export(
                                target = ExportTarget.Page(pageId = state.currentPageId),
                                format = ExportFormat.PNG
                            )
                        }
                    }
                }
                onClose()
            }
            MenuItem(context.getString(R.string.export_page_to, "JPEG")) {
                scope.launch {
                    snackManager.runWithSnack(
                        context.getString(
                            R.string.exporting_the_page_to, "JPG"
                        )
                    ) {
                        ExportEngine(context).export(
                            target = ExportTarget.Page(pageId = state.currentPageId),
                            format = ExportFormat.JPEG
                        )
                    }
                }
                onClose()
            }
            MenuItem(context.getString(R.string.export_page_to, "xopp")) {
                scope.launch {
                    snackManager.runWithSnack(
                        context.getString(
                            R.string.exporting_the_page_to, "xopp"
                        )
                    ) {
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
                MenuItem(context.getString(R.string.export_book_to, "PDF")) {
                    scope.launch {
                        snackManager.runWithSnack(
                            context.getString(
                                R.string.exporting_the_book_to, "PDF"
                            )
                        ) {
                            ExportEngine(context).export(
                                target = ExportTarget.Book(bookId = state.bookId),
                                format = ExportFormat.PDF
                            )
                        }
                    }
                    onClose()
                }
                MenuItem(context.getString(R.string.export_book_to, "PNG")) {
                    scope.launch {
                        snackManager.runWithSnack(
                            context.getString(
                                R.string.exporting_the_book_to, "PNG"
                            )
                        ) {
                            ExportEngine(context).export(
                                target = ExportTarget.Book(bookId = state.bookId),
                                format = ExportFormat.PNG
                            )
                        }
                    }
                    onClose()
                }
                MenuItem(context.getString(R.string.export_book_to, "xopp")) {
                    scope.launch {
                        snackManager.runWithSnack(
                            context.getString(
                                R.string.exporting_the_book_to, "xopp"
                            )
                        ) {
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
                    clearPageSignal.emit(Unit)
                    snackManager.displaySnack(
                        SnackConf(
                            text = context.getString(R.string.cleared_all_strokes), duration = 3000
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