package com.ethran.notable.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.data.datastore.A4_HEIGHT
import com.ethran.notable.data.datastore.A4_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.drawStroke
import io.shipbook.shipbooksdk.Log


private fun drawPageContent(
    context: Context,
    canvas: Canvas,
    page: Page,
    strokes: List<Stroke>,
    images: List<Image>,
    scroll: Offset,
    scaleFactor: Float
) {
    canvas.scale(scaleFactor, scaleFactor)
    val scaledScroll = scroll / scaleFactor
    drawBg(
        context,
        canvas,
        page.getBackgroundType(),
        page.background,
        scaledScroll,
        scaleFactor
    )

    for (image in images) {
        drawImage(context, canvas, image, -scaledScroll)
    }

    for (stroke in strokes) {
        drawStroke(canvas, stroke, -scaledScroll)
    }
}


fun drawCanvas(context: Context, pageId: String): Bitmap {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val pages = PageRepository(context)
    val (page, strokes) = pages.getWithStrokeById(pageId)
    val (_, images) = pages.getWithImageById(pageId)

    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50

    val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val width = strokeWidth.coerceAtLeast(SCREEN_WIDTH)

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    // Draw background
    drawBg(context, canvas, page.getBackgroundType(), page.background)

    // Draw strokes
    for (stroke in strokes) {
        drawStroke(canvas, stroke, Offset.Zero)
    }
    for (image in images) {
        drawImage(context, canvas, image, Offset.Zero)
    }
    return bitmap
}

fun PdfDocument.writePage(context: Context, number: Int, repo: PageRepository, id: String) {
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Exporting is done on main thread.")

    val (page, strokes) = repo.getWithStrokeById(id)
    //TODO: improve that function
    val (_, images) = repo.getWithImageById(id)

    //add 50 only if we are not cutting pdf on export.
    val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom)
        .toInt() + if (GlobalAppSettings.current.visualizePdfPagination) 0 else 50
    val strokeWidth = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::right).toInt() + 50
    val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH

    val contentHeight = strokeHeight.coerceAtLeast(SCREEN_HEIGHT)
    val pageHeight = (contentHeight * scaleFactor).toInt()

    if (GlobalAppSettings.current.paginatePdf) {
        var currentTop = 0
        while (currentTop < pageHeight) {
            // TODO: pageNumber are wrong
            val documentPage =
                startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, number).create())
            drawPageContent(
                context,
                documentPage.canvas,
                page,
                strokes,
                images,
                Offset(0f, currentTop.toFloat()),
                scaleFactor
            )
            finishPage(documentPage)
            currentTop += A4_HEIGHT
        }
    } else {
        val documentPage =
            startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, pageHeight, number).create())
        drawPageContent(
            context,
            documentPage.canvas,
            page,
            strokes,
            images,
            Offset.Zero,
            scaleFactor
        )
        finishPage(documentPage)
    }
}