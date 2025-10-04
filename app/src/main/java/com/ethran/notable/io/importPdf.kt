package com.ethran.notable.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ethran.notable.TAG
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.Log
import java.io.File


fun handlePdfImport(context: Context, folderId: String?, uri: Uri, copyFile: Boolean = true) {
    Log.v(TAG, "Importing PDF from $uri")
    ensureNotMainThread("Importing")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flag)
    val subfolder = BackgroundType.Pdf(0).folderName
    val fileToSave = if (copyFile) copyBackgroundToDatabase(context, uri, subfolder)
    else {
        val fileName = getFilePathFromUri(context, uri)
        if (fileName == null) {
            Log.e(TAG, "File name is null")
            showHint(
                "Couldn't determine file path. Does the app have permission to read external storage?",
                duration = 5000
            )
            return
        } else File(fileName)
    } //content://com.android.providers.media.documents/document/document%3A1000000754

    val pageRepo = PageRepository(context)
    val bookRepo = BookRepository(context)

    val notebookName = sanitizeNotebookName(fileToSave.nameWithoutExtension)

    val book = Notebook(
        title = notebookName,
        parentFolderId = folderId,
        defaultBackground = fileToSave.toString(),
        defaultBackgroundType = BackgroundType.AutoPdf.key
    )
    bookRepo.createEmpty(book)

    val numberOfPages = getPdfPageCount(fileToSave.toString())

    for (i in 0 until numberOfPages) {
        val page = Page(
            notebookId = book.id,
            background = fileToSave.toString(),
            backgroundType = if (copyFile) BackgroundType.Pdf(i).key else BackgroundType.AutoPdf.key
        )
        pageRepo.create(page)
        bookRepo.addPage(book.id, page.id)
    }
}

fun sanitizeNotebookName(raw: String, maxLen: Int = 100): String {
    var name = raw

    // Allow only letters, numbers, spaces, and dots
    name = name.replace(Regex("[^A-Za-z0-9. ]"), " ")

    // Collapse multiple spaces
    name = name.replace(Regex("\\s+"), " ")

    // Reduce multiple consecutive dots to a single dot
    name = name.replace(Regex("\\.+"), ".")

    // Trim whitespace from start and end
    name = name.trim()

    // Remove leading dot if present
    if (name.startsWith(".")) {
        name = name.removePrefix(".")
    }

    // Cut if too long
    if (name.length > maxLen) {
        name = name.take(maxLen).trimEnd()
    }

    return name
}
