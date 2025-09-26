package com.ethran.notable.io

import android.content.ContentUris
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.utils.UriUtils.getDataColumn
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.use

private val fileUtilsLog = ShipBook.getLogger("FileUtilsLogger")

// adapted from:
// https://stackoverflow.com/questions/71241337/copy-image-from-uri-in-another-folder-with-another-name-in-kotlin-android
fun createFileFromContentUri(context: Context, fileUri: Uri, outputDir: File): File {
    var fileName = ""

    // Get the display name of the file
    context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        fileName = cursor.getString(nameIndex)
    }

    // Extract the MIME type if needed
//    val fileType: String? = context.contentResolver.getType(fileUri)

    // Open the input stream
    val iStream: InputStream = context.contentResolver.openInputStream(fileUri)!!

    fileName = sanitizeFileName(fileName)
    val outputFile = File(outputDir, fileName)

    // Copy the input stream to the output file
    copyStreamToFile(iStream, outputFile)
    iStream.close()
    return outputFile
}

fun sanitizeFileName(fileName: String): String {
    return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
    inputStream.use { input ->
        FileOutputStream(outputFile).use { output ->
            val buffer = ByteArray(4 * 1024) // buffer size
            while (true) {
                val byteCount = input.read(buffer)
                if (byteCount < 0) break
                output.write(buffer, 0, byteCount)
            }
            output.flush()
        }
    }
}

fun getPdfPageCount(uri: String): Int {
    if (uri.isEmpty()) {
        fileUtilsLog.w("getPdfPageCount: Empty URI")
        return 0
    }
    val file = File(uri)
    if (!file.exists()) {
        fileUtilsLog.w("getPdfPageCount: File does not exist: $uri")
        return 0
    }

    return try {
        val fileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        if (fileDescriptor != null) {
            PdfRenderer(fileDescriptor).use { renderer ->
                renderer.pageCount
            }
        } else {
            fileUtilsLog.e("File descriptor is null for URI: $uri")
            0
        }
    } catch (e: Exception) {
        fileUtilsLog.e("Failed to open PDF: ${e.message}, for file $uri")
        logCallStack("getPdfPageCount")
        0
    }
}

suspend fun waitForFileAvailable(
    filePath: String,
    timeoutMs: Long = 5000
): Boolean {
    val file = File(filePath)
    val start = System.currentTimeMillis()
    var intervalMs: Long = 5
    var count = 1
    while (System.currentTimeMillis() - start < timeoutMs) {
        if (file.exists() && file.length() > 0) {
            return true
        }
        delay(intervalMs)
        intervalMs += count * count // Quadratic growth
        count++
    }
    return false
}

// Requires android.permission.READ_EXTERNAL_STORAGE
fun getFilePathFromUri(context: Context, uri: Uri): String? {
    return when {
        DocumentsContract.isDocumentUri(context, uri) -> {
            val docId = DocumentsContract.getDocumentId(uri)
            when {
                uri.authority?.contains("media") == true -> {
                    val split = docId.split(":")
                    val type = split[0]
                    val contentUri = when (type) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(split[1])
                    getDataColumn(context, contentUri, selection, selectionArgs)
                }

                uri.authority?.contains("downloads") == true -> {
                    val contentUri = ContentUris.withAppendedId(
                        "content://downloads/public_downloads".toUri(), docId.toLong()
                    )
                    getDataColumn(context, contentUri, null, null)
                }

                else -> null
            }
        }

        "content".equals(uri.scheme, ignoreCase = true) -> {
            getDataColumn(context, uri, null, null)
        }

        "file".equals(uri.scheme, ignoreCase = true) -> {
            uri.path
        }

        else -> null
    }
}

const val IN_IGNORED = 32768
fun fileObserverEventNames(event: Int): String {
    val names = mutableListOf<String>()
    if (event and FileObserver.ACCESS != 0) names += "ACCESS"
    if (event and FileObserver.ATTRIB != 0) names += "ATTRIB"
    if (event and FileObserver.CLOSE_NOWRITE != 0) names += "CLOSE_NOWRITE"
    if (event and FileObserver.CLOSE_WRITE != 0) names += "CLOSE_WRITE"
    if (event and FileObserver.CREATE != 0) names += "CREATE"
    if (event and FileObserver.DELETE != 0) names += "DELETE"
    if (event and FileObserver.DELETE_SELF != 0) names += "DELETE_SELF"
    if (event and FileObserver.MODIFY != 0) names += "MODIFY"
    if (event and FileObserver.MOVED_FROM != 0) names += "MOVED_FROM"
    if (event and FileObserver.MOVED_TO != 0) names += "MOVED_TO"
    if (event and FileObserver.MOVE_SELF != 0) names += "MOVE_SELF"
    if (event and FileObserver.OPEN != 0) names += "OPEN"
    if (event and FileObserver.ALL_EVENTS == event) names += "ALL_EVENTS"
    if (event and IN_IGNORED == event) names += "IN_IGNORED"
    if (names.isEmpty()) names += "Unknown: $event"
    return names.joinToString("|")
}