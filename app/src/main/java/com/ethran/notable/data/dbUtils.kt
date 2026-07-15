package com.ethran.notable.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.io.createFileFromContentUri
import com.ethran.notable.io.isImageUri
import com.ethran.notable.io.saveImageFromContentUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun getDbDir(): File {
    return getDbDirOrNull() ?: throw IllegalStateException(
        "Database directory could not be created or is not writable at " +
                "${dbDirPath().absolutePath}. " +
                "Grant 'All files access' to Notable in system settings and restart the app."
    )
}

/**
 * Like [getDbDir], but returns null instead of throwing when the directory cannot be
 * created or written to (missing permission, storage not mounted yet, etc.), so callers
 * can fall back to the welcome/setup screen instead of crashing.
 */
fun getDbDirOrNull(): File? {
    val dbDir = dbDirPath()
    if (!dbDir.exists() && !dbDir.mkdirs() && !dbDir.exists()) return null
    if (!dbDir.canWrite()) return null
    return dbDir
}

/**
 * The expected database directory path, without checking that it exists or is writable.
 * Use [getDbDir] or [getDbDirOrNull] when actual access is needed.
 */
fun dbDirPath(): File {
    val documentsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    return File(documentsDir, "notabledb")
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
suspend fun deletePage(appRepository: AppRepository, pageId: String, filesDir: File) = withContext(Dispatchers.IO) {
    val page = appRepository.pageRepository.getById(pageId) ?: return@withContext
    val proxy = appRepository.kvProxy
    val settings = proxy.get(APP_SETTINGS_KEY, AppSettings.serializer())

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
    appRepository.pageRepository.delete(pageId)
    coroutineScope {
        launch {
            val imgFileThumb = File(filesDir, "pages/previews/thumbs/$pageId")
            if (imgFileThumb.exists()) {
                imgFileThumb.delete()
            }
            val imgFileFull = File(filesDir, "pages/previews/full/$pageId")
            if (imgFileFull.exists()) {
                imgFileFull.delete()
            }
        }

    }
}
