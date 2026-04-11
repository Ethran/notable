package com.ethran.notable.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("importPdf")

fun isPdfFile(mimeType: String?, fileName: String?): Boolean {
    return mimeType == "application/pdf" || fileName?.endsWith(
        ".pdf", ignoreCase = true
    ) == true
}


@WorkerThread
fun handleFileSaving(
    context: Context,
    uri: Uri,
    options: ImportOptions,
): File? {
    ensureNotMainThread("Importing")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flag)
    val subfolder = BackgroundType.Pdf(0).folderName
    return if (!options.linkToExternalFile) copyBackgroundToDatabase(context, uri, subfolder)
    else {
        val fileName = getFilePathFromUri(context, uri)
        if (fileName == null) {
            log.e("Couldn't determine file path. Missing permission for external storage?")
            return null
        } else File(fileName)
    }
}

@WorkerThread
suspend fun importPdf(
    fileToSave: File,
    options: ImportOptions,
    savePageToDatabase: suspend (PageContent) -> Unit
): String {
    log.v("Importing PDF from")

    val numberOfPages = getPdfPageCount(fileToSave.toString())

    for (i in 0 until numberOfPages) {
        val page = Page(
            notebookId = options.saveToBookId,
            background = fileToSave.toString(),
            backgroundType = if (options.linkToExternalFile) BackgroundType.AutoPdf.key
            else BackgroundType.Pdf(i).key
        )
        savePageToDatabase(PageContent(page, emptyList(), emptyList()))
    }
    return "Imported ${fileToSave.name}"
}