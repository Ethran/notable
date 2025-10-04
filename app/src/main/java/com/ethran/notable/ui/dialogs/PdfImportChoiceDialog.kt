package com.ethran.notable.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PdfImportChoiceDialog(
    uri: Uri,
    onCopy: (Uri) -> Unit,
    onObserve: (Uri) -> Unit,
) {
    ShowConfirmationDialog(
        title = "Import PDF Background",
        content = {
            Column {
                Text(
                    text = "Do you want to copy or observe the PDF?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Observe: ",
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = FontStyle.Italic
                )
                Text(
                    text = "The app will set up a listener for changes to the file. Useful for files that change often (e.g., when using LaTeX).",
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• Copy: ",
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = FontStyle.Italic
                )
                Text(
                    text = "The app will copy the file to its database. Use this for safe and static storage.",
                    fontSize = 14.sp
                )
            }
        },
        onConfirm = { onCopy(uri) },
        onCancel = { onObserve(uri) },
        confirmButtonText = "Copy",
        cancelButtonText = "Observe"
    )
}
