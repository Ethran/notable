package com.ethran.notable.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.shipbook.shipbooksdk.Log

@Composable
fun PdfImportChoiceDialog(
    uri: Uri,
    onCopy: (Uri) -> Unit,
    onObserve: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val pathLength = remember(uri) { uri.path?.length ?: 0 }
    Log.d("PdfImportChoiceDialog", "Path Length: $pathLength")
    val pathTooLong = pathLength > 100

    ShowConfirmationDialog(
        title = "Import PDF Background",
        content = {
            Column {
                Text(
                    text = "Do you want to copy or observe the PDF?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // --- Observe Option ---
                Text(
                    text = "• Observe:",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "The app will link to the original file. This is useful for files that change often (e.g., from cloud services or LaTeX).",
                    fontSize = 14.sp
                )
                // Add a warning if the file path is too long
                if (pathTooLong) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Warning: The file path is very long ($pathLength characters). Observing may fail on some systems.",
                        color = MaterialTheme.colors.error, // Use theme's error color for emphasis
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // --- Copy Option ---
                Text(
                    text = "• Copy:",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "The app will make its own copy of the file. Use this for safe, static storage.",
                    fontSize = 14.sp
                )
            }
        },
        onConfirm = { onCopy(uri) },
        onCancel = { onObserve(uri) },
        onDismiss = { onDismiss() },
        confirmButtonText = "Copy",
        cancelButtonText = "Observe"
    )
}