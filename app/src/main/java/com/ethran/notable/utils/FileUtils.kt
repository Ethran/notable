package com.ethran.notable.utils

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.use

fun copyBackgroundToDatabase(context: Context, fileUri: Uri, subfolder: String): File {
    var outputDir = ensureBackgroundsFolder()
    outputDir = File(outputDir, subfolder)
    if (!outputDir.exists())
        outputDir.mkdirs()
    return createFileFromContentUri(context, fileUri, outputDir)
}

fun copyImageToDatabase(context: Context, fileUri: Uri, subfolder: String? = null): File {
    var outputDir = ensureImagesFolder()
    if (subfolder != null) {
        outputDir = File(outputDir, subfolder)
        if (!outputDir.exists())
            outputDir.mkdirs()
    }
    return createFileFromContentUri(context, fileUri, outputDir)
}

// adapted from:
// https://stackoverflow.com/questions/71241337/copy-image-from-uri-in-another-folder-with-another-name-in-kotlin-android
private fun createFileFromContentUri(context: Context, fileUri: Uri, outputDir: File): File {
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

fun getDbDir(): File {
    val documentsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val dbDir = File(documentsDir, "notabledb")
    if (!dbDir.exists()) {
        dbDir.mkdirs()
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


fun getPdfPageCount(uri: String): Int {
    if (uri.isEmpty())
        return 0
    val file = File(uri)
    if (!file.exists()) return 0

    return try {
        val fileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        if (fileDescriptor != null) {
            PdfRenderer(fileDescriptor).use { renderer ->
                renderer.pageCount
            }
        } else {
            Log.e(TAG, "File descriptor is null for URI: $uri")
            0
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open PDF: ${e.message}, for file $uri")
        logCallStack("getPdfPageCount")
        0
    }
}
