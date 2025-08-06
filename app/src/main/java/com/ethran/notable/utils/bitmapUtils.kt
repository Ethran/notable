package com.ethran.notable.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.ethran.notable.R
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


// Why it is needed? I try to removed it, and sharing bimap seems to work.
class Provider : FileProvider(R.xml.file_paths)

fun shareBitmap(context: Context, bitmap: Bitmap) {
    val bmpWithBackground = createBitmap(bitmap.width, bitmap.height)
    val canvas = Canvas(bmpWithBackground)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    val cachePath = File(context.cacheDir, "images")
    Log.i(TAG, cachePath.toString())
    cachePath.mkdirs()
    try {
        val stream = FileOutputStream(File(cachePath, "share.png"))
        bmpWithBackground.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.close()
    } catch (e: IOException) {
        e.printStackTrace()
        return
    }

    val bitmapFile = File(cachePath, "share.png")
    val contentUri = FileProvider.getUriForFile(
        context,
        "com.ethran.notable.provider", //(use your app signature + ".provider" )
        bitmapFile
    )

    // Use ShareCompat for safe sharing
    val shareIntent = ShareCompat.IntentBuilder.from(context as Activity)
        .setStream(contentUri)
        .setType("image/png")
        .intent
        .apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    context.startActivity(Intent.createChooser(shareIntent, "Choose an app"))
}

fun loadBackgroundBitmap(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
    if (filePath.isEmpty())
        return null
    Log.v(TAG, "Reloading background, path: $filePath, scale: $scale")
    val file = File(filePath)
    if (!file.exists()) {
        Log.e(TAG, "getOrLoadBackground: File does not exist at path: $filePath")
        return null
    }

    val newBitmap: ImageBitmap? = try {
        if (filePath.endsWith(".pdf", ignoreCase = true)) {
            // PDF rendering
            val fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fileDescriptor).use { renderer ->
                if (pageNumber < 0 || pageNumber >= renderer.pageCount) {
                    Log.e(
                        TAG,
                        "getOrLoadBackground: Invalid page number $pageNumber (total: ${renderer.pageCount})"
                    )
                    return null
                }

                renderer.openPage(pageNumber).use { pdfPage ->
                    val targetWidth = SCREEN_WIDTH * scale
                    val scaleFactor = targetWidth / pdfPage.width

                    val width = (pdfPage.width * scaleFactor).toInt()
                    val height = (pdfPage.height * scaleFactor).toInt()

                    val bitmap = createBitmap(width, height)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        } else {
            // Image file
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.e(TAG, "getOrLoadBackground: Error loading background - ${e.message}", e)
        null
    }
    return newBitmap?.asAndroidBitmap()
}


// move to SelectionState?
fun copyBitmapToClipboard(context: Context, bitmap: Bitmap) {
    // Save bitmap to cache and get a URI
    val uri = saveBitmapToCache(context, bitmap) ?: return

    // Grant temporary permission to read the URI
    context.grantUriPermission(
        context.packageName,
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    )

    // Create a ClipData holding the URI
    val clipData = ClipData.newUri(context.contentResolver, "Image", uri)

    // Set the ClipData to the clipboard
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clipData)
}

fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
    val bmpWithBackground = createBitmap(bitmap.width, bitmap.height)
    val canvas = Canvas(bmpWithBackground)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    val cachePath = File(context.cacheDir, "images")
    Log.i(TAG, cachePath.toString())
    cachePath.mkdirs()
    try {
        val stream =
            FileOutputStream("$cachePath/share.png")
        bmpWithBackground.compress(
            Bitmap.CompressFormat.PNG,
            100,
            stream
        )
        stream.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }

    val bitmapFile = File(cachePath, "share.png")
    return FileProvider.getUriForFile(
        context,
        "com.ethran.notable.provider", //(use your app signature + ".provider" )
        bitmapFile
    )
}