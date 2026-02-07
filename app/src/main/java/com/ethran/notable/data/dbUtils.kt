package com.ethran.notable.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.io.createFileFromContentUri
import com.ethran.notable.io.isImageUri
import com.ethran.notable.io.saveImageFromContentUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

fun getDbDir(): File {
    val documentsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val dbDir = File(documentsDir, "notabledb")
    if (!dbDir.exists()) {
        dbDir.mkdirs()
    }
    if (!dbDir.canWrite()) {
        throw IllegalStateException("Database directory is not writable")
    }
    return dbDir
}

fun ensureImagesFolder(): File {
    val dbDir = getDbDir()
    val imagesDir = File(dbDir, "images")
    if (!imagesDir.exists()) {
        imagesDir.mkdirs()
    }
    return imagesDir
}

fun ensureBackgroundsFolder(): File {
    val dbDir = getDbDir()
    val backgroundsDir = File(dbDir, "backgrounds")
    if (!backgroundsDir.exists()) {
        backgroundsDir.mkdirs()
    }
    return backgroundsDir
}

fun ensurePreviewsFullFolder(context: Context): File {
    val dir = File(context.filesDir, "pages/previews/full")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}


fun copyBackgroundToDatabase(context: Context, fileUri: Uri, subfolder: String): File {
    var outputDir = ensureBackgroundsFolder()
    outputDir = File(outputDir, subfolder)
    if (!outputDir.exists())
        outputDir.mkdirs()
    return if (isImageUri(context, fileUri))
    // make sure that image is not too large
        saveImageFromContentUri(context, fileUri, outputDir)
    else
        createFileFromContentUri(context, fileUri, outputDir)
}

fun copyImageToDatabase(context: Context, fileUri: Uri, subfolder: String? = null): File {
    var outputDir = ensureImagesFolder()
    if (subfolder != null) {
        outputDir = File(outputDir, subfolder)
        if (!outputDir.exists())
            outputDir.mkdirs()
    }
    return saveImageFromContentUri(context, fileUri, outputDir)
}


// TODO move this to repository
fun deletePage(context: Context, pageId: String) {
    val appRepository = AppRepository(context)
    val page = appRepository.pageRepository.getById(pageId) ?: return
    val proxy = appRepository.kvProxy
    val settings = proxy.get(APP_SETTINGS_KEY, AppSettings.serializer())


    runBlocking {
        // remove from book
        if (page.notebookId != null) {
            appRepository.bookRepository.removePage(page.notebookId, pageId)
        }

        // remove from quick nav
        if (settings != null && settings.quickNavPages.contains(pageId)) {
            proxy.setKv(
                APP_SETTINGS_KEY,
                settings.copy(quickNavPages = settings.quickNavPages - pageId),
                AppSettings.serializer()
            )
        }

        launch {
            appRepository.pageRepository.delete(pageId)
        }
        launch {
            val imgFile = File(context.filesDir, "pages/previews/thumbs/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }
        launch {
            val imgFile = File(context.filesDir, "pages/previews/full/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }

    }
}
