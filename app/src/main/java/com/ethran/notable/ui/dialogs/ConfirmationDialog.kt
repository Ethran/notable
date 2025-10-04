package com.ethran.notable.ui.dialogs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ShowSimpleConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel"
) {
    ShowConfirmationDialog(
        title = title,
        content = { Text(text = message, fontSize = 16.sp) },
        onConfirm = onConfirm,
        onCancel = onCancel,
        onDismiss = onCancel,
        confirmButtonText = confirmButtonText,
        cancelButtonText = cancelButtonText
    )
}


@Composable
fun ShowConfirmationDialog(
    title: String,
    content: @Composable (() -> Unit),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit = onCancel,
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel"
) {
    Dialog(onDismissRequest = { onDismiss() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            content()
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                ActionButton(text = cancelButtonText, onClick = onCancel)
                ActionButton(text = confirmButtonText, onClick = onConfirm)
            }
        }
    }
}

@Composable
fun ShowExportDialog(
    snackManager: SnackState,
    bookId: String,
    context: Context,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = { onCancel() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Choose Export Format", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                text = "Select the format in which you want to export the book:\n" + "- Xopp: Preserves all data and can be imported. " + "However, if opened and saved by Xournal++, tool-specific information will be lost, " + "and all strokes will be interpreted as ballpoint pen.\n" + "- PDF: A standard format for document sharing.",
                fontSize = 16.sp
            )
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                ActionButton(
                    text = "Cancel", onClick = onCancel
                )
                ActionButton(
                    text = "Export as PDF", onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            snackManager.runWithSnack(
                                "Exporting book to PDF.."
                            ) {
                                ExportEngine(context).export(
                                    target = ExportTarget.Book(bookId = bookId),
                                    format = ExportFormat.PDF,
                                    options = ExportOptions(
                                        copyToClipboard = false,
                                    )
                                )

                            }

                        }
                        onConfirm()
                    })
                ActionButton(
                    text = "Export as Xopp", onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            snackManager.runWithSnack(
                                "Exporting the book to xopp..."
                            ) {
                                ExportEngine(context).export(
                                    target = ExportTarget.Book(bookId = bookId),
                                    format = ExportFormat.XOPP,
                                )
                            }
                        }

                        onConfirm()
                    })
            }
        }
    }
}
