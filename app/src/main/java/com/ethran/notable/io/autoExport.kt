package com.ethran.notable.io

import androidx.core.net.toUri
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.ui.SnackState.Companion.logAndShowError
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("autoExport")

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
    exportEngine: ExportEngine,
    bookId: String,
    bookRepository: BookRepository,
) {
    CoroutineScope(Dispatchers.IO).launch {
        val uriStr = bookRepository.getById(bookId)?.linkedExternalUri
        if (!uriStr.isNullOrBlank()) {
            try {
                log.i("Exporting page to linked file, dictionary: $uriStr")
                exportEngine.export(
                    target = ExportTarget.Book(bookId),
                    format = ExportFormat.XOPP,
                    options = ExportOptions(
                        copyToClipboard = false,
                        targetFolderUri = uriStr.toUri(),
                        overwrite = true
                    )
                )
                log.i("Export successful")
            } catch (e: Exception) {
                logAndShowError(
                    "exportToLinkedFile",
                    "Error when exporting page to linked file: ${e.message}"
                )
            }
        }
    }
}