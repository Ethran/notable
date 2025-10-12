package com.ethran.notable.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.TAG
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.Log
import java.io.File

fun isPdfFile(mimeType: String?, fileName: String?): Boolean {
    return mimeType == "application/pdf" || fileName?.endsWith(
        ".pdf",
        ignoreCase = true
    ) == true
}

@WorkerThread
fun importPdf(
    context: Context,
    uri: Uri,
    options: ImportOptions,
    savePageToDatabase: (PageContent) -> Unit
): String {
    Log.v(TAG, "Importing PDF from $uri")
    ensureNotMainThread("Importing")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flag)
    val subfolder = BackgroundType.Pdf(0).folderName
    val fileToSave =
        if (!options.linkToExternalFile) copyBackgroundToDatabase(context, uri, subfolder)
        else {
            val fileName = getFilePathFromUri(context, uri)
            if (fileName == null) {
                Log.e(TAG, "File name is null")
                return "Couldn't determine file path. Does the app have permission to read external storage?"
            } else File(fileName)
        }

    val numberOfPages = getPdfPageCount(fileToSave.toString())

    for (i in 0 until numberOfPages) {
        val page = Page(
            notebookId = options.saveToBookId,
            background = fileToSave.toString(),
            backgroundType = if (options.linkToExternalFile) BackgroundType.Pdf(i).key else BackgroundType.AutoPdf.key
        )
        savePageToDatabase(PageContent(page, emptyList(), emptyList()))
    }
    return "Imported ${fileToSave.name}"
}