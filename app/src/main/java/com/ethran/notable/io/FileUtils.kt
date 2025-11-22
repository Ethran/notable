package com.ethran.notable.io

import android.content.ContentUris
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
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
import java.text.Normalizer
import kotlin.use

private val fileUtilsLog = ShipBook.getLogger("FileUtilsLogger")


fun getLinkedFilesDir(): File {
    val documentsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val dbDir = File(documentsDir, "/notable/Linked")
    if (!dbDir.exists()) {
        dbDir.mkdirs()
    }
    return dbDir
}


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

fun sanitizeFileName(raw: String, maxLen: Int = 80): String {
    // Normalize accents → é → e, Ł → L, etc.
    var name = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "") // remove diacritics

    // Replace illegal filename characters with " "
    name = name.replace(Regex("""[\\/:*?"<>|]"""), " ")

    // Collapse multiple underscores & spaces into one
    name = name.replace(Regex("[ ]+"), " ").trim()
    name = name.replace(Regex("[_]+"), "_").trim()

    // Prevent names like ".hidden" by stripping leading dots
    name = name.trim('.')

    // Enforce max length and fallback name
    if (name.length > maxLen) {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1)
        // No usable extension found, fall back to simple truncation
            name = name.take(maxLen).trimEnd()
        else {
            val ext = name.substring(dot)
            val baseName = name.take(dot)
            name = baseName.take(maxLen - ext.length).trimEnd().trimEnd('.') + ext
        }
    }
    if (name.isBlank()) {
        name = "notable-export"
    }
    return name
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

// Requires android.permission.READ_EXTERNAL_STORAGE (pre-Android 13) and file actually readable
fun getFilePathFromUri(context: Context, uri: Uri): String? {
    try {
        return when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrElse {
                        fileUtilsLog.e("getFilePathFromUri: getDocumentId failed for uri=$uri: ${it.message}", it)
                        return null
                    }

                when {
                    // MediaStore provider
                    uri.authority?.contains("media") == true -> {
                        val split = docId.split(":")
                        if (split.size < 2) {
                            fileUtilsLog.w("getFilePathFromUri: Unexpected docId for media: '$docId', uri=$uri")
                            return null
                        }
                        val type = split[0]
                        val id = split[1]
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        val selection = "_id=?"
                        val selectionArgs = arrayOf(id)
                        getDataColumn(context, contentUri, selection, selectionArgs).also {
                            if (it == null) {
                                fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for media id=$id, uri=$uri, contentUri=$contentUri, docId='$docId'")
                            }
                        }
                    }

                    // Downloads provider
                    uri.authority?.contains("downloads") == true -> {
                        val contentUri = runCatching {
                            ContentUris.withAppendedId(
                                "content://downloads/public_downloads".toUri(),
                                docId.toLong()
                            )
                        }.getOrElse {
                            fileUtilsLog.w("getFilePathFromUri: Bad downloads docId '$docId' for uri=$uri: ${it.message}")
                            return null
                        }
                        getDataColumn(context, contentUri, null, null).also {
                            if (it == null) {
                                fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for downloads contentUri=$contentUri (orig uri=$uri, docId='$docId')")
                            }
                        }
                    }

                    // External storage provider (primary/non-primary volumes)
                    uri.authority == "com.android.externalstorage.documents" -> {
                        // docId examples:
                        // - "primary:Download/file.pdf"
                        // - "home:Documents/file.pdf"
                        // - "0000-0000:Android/data/..."
                        val split = docId.split(":")
                        val type = split.getOrNull(0).orEmpty()
                        val relative = split.getOrNull(1).orEmpty()

                        val basePath: String? = when {
                            type.equals("primary", ignoreCase = true) -> {
                                Environment.getExternalStorageDirectory().absolutePath
                            }
                            type.equals("home", ignoreCase = true) -> {
                                // "home" generally maps under primary; treat like primary root
                                Environment.getExternalStorageDirectory().absolutePath
                            }
                            type.isNotEmpty() -> {
                                // Non-primary (SD card/USB) volume id; best-effort mount path
                                // Commonly /storage/<UUID>/...
                                "/storage/$type"
                            }
                            else -> null
                        }

                        if (basePath == null) {
                            fileUtilsLog.w("getFilePathFromUri: externalstorage: unknown volume for docId='$docId', uri=$uri")
                            null
                        } else {
                            val candidate = if (relative.isNotEmpty()) {
                                File(basePath, relative).absolutePath
                            } else {
                                basePath
                            }
                            val f = File(candidate)
                            if (f.exists()) {
                                candidate
                            } else {
                                fileUtilsLog.w("getFilePathFromUri: externalstorage resolved path does not exist: $candidate (uri=$uri, docId='$docId')")
                                null
                            }
                        }
                    }

                    else -> {
                        fileUtilsLog.w("getFilePathFromUri: Unhandled document authority='${uri.authority}' for uri=$uri, docId='$docId'")
                        null
                    }
                }
            }

            "content".equals(uri.scheme, ignoreCase = true) -> {
                getDataColumn(context, uri, null, null).also {
                    if (it == null) {
                        fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for content uri=$uri (provider=${uri.authority})")
                    }
                }
            }

            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path.also {
                    if (it.isNullOrEmpty()) {
                        fileUtilsLog.w("getFilePathFromUri: file scheme but empty path for uri=$uri")
                    }
                }
            }

            else -> {
                fileUtilsLog.w("getFilePathFromUri: Unsupported scheme='${uri.scheme}' authority='${uri.authority}' for uri=$uri")
                null
            }
        }
    } catch (se: SecurityException) {
        fileUtilsLog.e("getFilePathFromUri: SecurityException for uri=$uri: ${se.message}", se)
        return null
    } catch (e: Exception) {
        fileUtilsLog.e("getFilePathFromUri: Unexpected error for uri=$uri: ${e.message}", e)
        return null
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