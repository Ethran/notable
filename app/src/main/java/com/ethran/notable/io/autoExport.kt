package com.ethran.notable.io

import android.content.Context
import androidx.core.net.toUri
import com.ethran.notable.TAG
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.ui.SnackState.Companion.logAndShowError
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * Exports a notebook to its externally linked file, if one is configured.
 *
 * This function checks if the notebook specified by [bookId] has a linked file path (`linkedExternalUri`).
 * If it does, the function launches a coroutine to export the notebook to that location.
 * The export is performed in the `.xopp` format and will overwrite any existing file at the destination.
 * If an error occurs during the export, a snackbar with an error message is displayed to the user.
 *
 * @param context The application context, used by the [ExportEngine].
 * @param bookId The ID of the notebook to be exported. If null, the function does nothing.
 * @param bookRepository The repository to access notebook data, specifically to retrieve the linked file URI.
 */
fun exportToLinkedFile(
    context: Context,
    bookId: String?,
    bookRepository: BookRepository,
) {
    if (bookId == null)
        return

    val uriStr = bookRepository.getById(bookId)?.linkedExternalUri
    if (!uriStr.isNullOrBlank()) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Exporting page to linked file, dictionary: $uriStr")
                ExportEngine(context).export(
                    target = ExportTarget.Book(bookId),
                    format = ExportFormat.XOPP,
                    options = ExportOptions(
                        copyToClipboard = false,
                        targetFolderUri = uriStr.toUri(),
                        overwrite = true
                    )
                )
                Log.i(TAG, "Export successful")
            } catch (e: Exception) {
                logAndShowError(
                    "exportToLinkedFile",
                    "Error when exporting page to linked file: ${e.message}"
                )
            }
        }
    }
}