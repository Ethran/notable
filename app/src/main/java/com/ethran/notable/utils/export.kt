package com.ethran.notable.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import com.ethran.notable.TAG
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.PageRepository
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

suspend fun exportBook(context: Context, bookId: String): String {
    val book = BookRepository(context).getById(bookId) ?: return "Book ID not found"
    val pages = PageRepository(context)
    Log.v(TAG, "Exporting book: " + book.title)

    val result = saveFile(context, book.title, "pdf") { outputStream ->
        val document = PdfDocument()
        book.pageIds.forEachIndexed { i, pageId ->
            document.writePage(context, i + 1, pages, pageId)
        }
        document.writeTo(outputStream)
        document.close()
    }

    copyBookPdfLinkForObsidian(context, bookId, book.title)
    return result
}

suspend fun exportPage(context: Context, pageId: String): String {
    val pages = PageRepository(context)
    val result = saveFile(context, "notable-page-${pageId}", "pdf") { outputStream ->
        val document = PdfDocument()
        document.writePage(context, 1, pages, pageId)
        document.writeTo(outputStream)
        document.close()
    }
    return result
}

suspend fun exportBookToPng(context: Context, bookId: String): String {
    val book = BookRepository(context).getById(bookId) ?: return "Book ID not found"
    book.pageIds.forEachIndexed { _, pageId ->
        val bitmap = drawCanvas(context, pageId)
        saveFile(context, pageId, "png", book.title) { outputStream ->
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                bitmap.recycle()
            } catch (e: Exception) {
                io.shipbook.shipbooksdk.Log.e(TAG + "ExportPNG", "Error saving PNG: ${e.message}")
                throw e
            }
        }
    }
    return "Book saved successfully at notable/${book.title}"
}


suspend fun exportPageToJpeg(context: Context, pageId: String): String {
    val bitmap = drawCanvas(context, pageId)

    return saveFile(context, "notable-page-${pageId}", "jpg") { outputStream ->
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            bitmap.recycle()
        } catch (e: Exception) {
            io.shipbook.shipbooksdk.Log.e(TAG + "ExportJpeg", "Error saving JPEG: ${e.message}")
            throw e
        }
    }
}

suspend fun exportPageToPng(context: Context, pageId: String): String {
    val bitmap = drawCanvas(context, pageId)

    return saveFile(context, "notable-page-${pageId}", "png") { outputStream ->
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            bitmap.recycle()
            copyPagePngLinkForObsidian(context, pageId)
        } catch (e: Exception) {
            io.shipbook.shipbooksdk.Log.e(TAG + "ExportPNG", "Error saving PNG: ${e.message}")
            throw e
        }
    }
}

suspend fun saveFile(
    context: Context,
    fileName: String,
    format: String,
    dictionary: String = "",
    generateContent: (OutputStream) -> Unit
): String = withContext(Dispatchers.IO) {
    try {
        val mimeType = when (format.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> return@withContext "Unsupported file format"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$fileName.$format")
            put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/Notable/" + dictionary
            )
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            ?: throw IOException("Failed to create Media Store entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            generateContent(outputStream)
        }

        "File saved successfully as $fileName.$format"
    } catch (e: SecurityException) {
        Log.e(TAG + "SaveFile", "Permission error: ${e.message}")
        "Permission denied. Please allow storage access and try again."
    } catch (e: IOException) {
        Log.e(TAG + "SaveFile", "I/O error while saving file: ${e.message}")
        "An error occurred while saving the file."
    } catch (e: Exception) {
        Log.e(TAG + "SaveFile", "Unexpected error: ${e.message}")
        "Unexpected error occurred. Please try again."
    }
}

fun copyPagePngLinkForObsidian(context: Context, pageId: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val textToCopy = """
       [[../attachments/Notable/Pages/notable-page-${pageId}.png]]
       [[Notable Link][notable://page-${pageId}]]
   """.trimIndent()
    val clip = ClipData.newPlainText("Notable Page Link", textToCopy)
    clipboard.setPrimaryClip(clip)
}


fun copyBookPdfLinkForObsidian(context: Context, bookId: String, bookName: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val textToCopy = """
        [[../attachments/Notable/Notebooks/${bookName}.pdf]]
        [[Notable Book Link][notable://book-${bookId}]]
    """.trimIndent()
    val clip = ClipData.newPlainText("Notable Book PDF Link", textToCopy)
    clipboard.setPrimaryClip(clip)
}