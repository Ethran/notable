package com.ethran.notable.io

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


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
        Log.e(TAG, "Failed to save shared image: ${e.message}", e)
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

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
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
        Log.e(TAG, "Failed to save PDF preview image: ${e.message}", e)
    }

    val bitmapFile = File(cachePath, "share.png")
    return FileProvider.getUriForFile(
        context,
        "com.ethran.notable.provider", //(use your app signature + ".provider" )
        bitmapFile
    )
}
