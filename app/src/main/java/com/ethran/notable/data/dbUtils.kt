package com.ethran.notable.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ethran.notable.io.createFileFromContentUri
import java.io.File

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
