package com.ethran.notable.editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.ethran.notable.R
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path


private val log = ShipBook.getLogger("bitmapUtils")

// Why it is needed? I try to removed it, and sharing bimap seems to work.
class Provider : FileProvider(R.xml.file_paths)


fun persistBitmapFull(context: Context, bitmap: Bitmap, pageID: String) {
    val file = File(context.filesDir, "pages/previews/full/$pageID")
    Files.createDirectories(Path(file.absolutePath).parent)

    file.outputStream().buffered().use { os ->
        val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        if (!success) {
            log.e("Failed to compress bitmap")
            logCallStack("persistBitmapFull")
        }
    }
}

fun persistBitmapThumbnail(context: Context, bitmap: Bitmap, pageID: String) {
    val file = File(context.filesDir, "pages/previews/thumbs/$pageID")
    Files.createDirectories(Path(file.absolutePath).parent)
    val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
    val scaledBitmap = bitmap.scale(500, (500 * ratio).toInt(), false)

    file.outputStream().buffered().use { os ->
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, os)
    }

    if (scaledBitmap != bitmap) {
        scaledBitmap.recycle()
    }
}

fun loadPersistBitmap(context: Context, pageID: String): Bitmap? {
    val imgFile = File(context.filesDir, "pages/previews/full/$pageID")
    val imgBitmap: Bitmap?
    return if (imgFile.exists()) {
        imgBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
        if (imgBitmap != null) {
            log.i("Initial Bitmap for page rendered from cache")
        } else
            log.i("Cannot read cache image")
        imgBitmap
    } else {
        log.i("Cannot find cache image")
        null
    }
}