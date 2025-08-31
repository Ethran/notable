package com.ethran.notable.ui.components

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
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.io.XoppFile
import com.ethran.notable.ui.modals.ActionButton
import com.ethran.notable.io.exportBook
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
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel"
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
                text = "Select the format in which you want to export the book:\n" +
                        "- Xopp: Preserves all data and can be imported. " +
                        "However, if opened and saved by Xournal++, tool-specific information will be lost, " +
                        "and all strokes will be interpreted as ballpoint pen.\n" +
                        "- PDF: A standard format for document sharing.",
                fontSize = 16.sp
            )
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                ActionButton(
                    text = "Cancel",
                    onClick = onCancel
                )
                ActionButton(
                    text = "Export as PDF",
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val removeSnack = snackManager.displaySnack(
                                SnackConf(
                                    text = "Exporting book to PDF...",
                                    id = "exportSnack"
                                )
                            )
                            val message = exportBook(context, bookId)
                            removeSnack()
                            snackManager.displaySnack(
                                SnackConf(text = message, duration = 2000)
                            )
                        }
                        onConfirm()
                    })
                ActionButton(
                    text = "Export as Xopp",
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val removeSnack = snackManager.displaySnack(
                                SnackConf(
                                    text = "Exporting book to Xopp format...",
                                    id = "exportSnack"
                                )
                            )
                            XoppFile.exportBook(context, bookId)
                            removeSnack()
                        }
                        onConfirm()
                    }
                )
            }
        }
    }
}
