package com.ethran.notable.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.BackgroundType.Native
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.drawStroke
import com.ethran.notable.utils.ensureNotMainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

sealed class RenderTarget {
    object Full : RenderTarget()
    data class Thumbnail(val maxWidthPx: Int, val maxHeightPx: Int) : RenderTarget()
}

@Singleton
class PageContentRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageRepo: PageRepository,
    private val appRepository: AppRepository
) {
    data class PageContent(
        val page: Page,
        val strokes: List<Stroke>,
        val images: List<Image>
    )

    suspend fun renderPageBitmap(pageId: String, target: RenderTarget): Bitmap {
        ensureNotMainThread("PageContentRenderer")
        val data = loadPageContent(pageId)

        return withContext(Dispatchers.Default) {
            val (contentWidth, contentHeight) = computeContentDimensions(data)
            val size = resolveRenderSize(contentWidth, contentHeight, target)

            createBitmap(size.width, size.height).also { bitmap ->
                drawPage(
                    canvas = Canvas(bitmap),
                    data = data,
                    scroll = Offset.Zero,
                    scaleFactor = size.scale,
                    backgroundType = data.page.getBackgroundType()
                )
            }
        }
    }

    private data class RenderSize(
        val width: Int,
        val height: Int,
        val scale: Float
    )

    suspend fun loadPageContent(pageId: String): PageContent = withContext(Dispatchers.IO) {
        val (page, strokes) = pageRepo.getWithStrokeById(pageId)
        val (_, images) = pageRepo.getWithImageById(pageId)
        PageContent(page, strokes, images)
    }

    suspend fun resolveExportBackgroundType(data: PageContent): BackgroundType {
        return data.page.notebookId?.let { bookId ->
            val pageNumber = withContext(Dispatchers.IO) {
                appRepository.getPageNumber(bookId, data.page.id)
            }
            data.page.getBackgroundType().resolveForExport(pageNumber)
        } ?: Native
    }

    suspend fun drawPage(
        canvas: Canvas,
        data: PageContent,
        scroll: Offset,
        scaleFactor: Float,
        backgroundType: BackgroundType
    ) {
        withContext(Dispatchers.Default) {
            canvas.scale(scaleFactor, scaleFactor)
            val scaledScroll = scroll / scaleFactor
            drawBg(
                context = context,
                canvas = canvas,
                backgroundType = backgroundType,
                background = data.page.background,
                scroll = scaledScroll,
                scale = scaleFactor
            )
            data.images.forEach { drawImage(context, canvas, it, -scaledScroll) }
            data.strokes.forEach { drawStroke(canvas, it, -scaledScroll) }
        }
    }

    // Returns (width, height)
    fun computeContentDimensions(data: PageContent): Pair<Int, Int> {
        if (data.strokes.isEmpty() && data.images.isEmpty()) {
            return SCREEN_WIDTH to SCREEN_HEIGHT
        }

        val strokeBottom = data.strokes.maxOfOrNull { it.bottom.toInt() } ?: 0
        val strokeRight = data.strokes.maxOfOrNull { it.right.toInt() } ?: 0
        val imageBottom = data.images.maxOfOrNull { it.y + it.height } ?: 0
        val imageRight = data.images.maxOfOrNull { it.x + it.width } ?: 0

        val rawHeight = maxOf(strokeBottom, imageBottom) +
            if (GlobalAppSettings.current.visualizePdfPagination) 0 else 50
        val rawWidth = maxOf(strokeRight, imageRight) + 50

        val height = rawHeight.coerceAtLeast(SCREEN_HEIGHT)
        val width = rawWidth.coerceAtLeast(SCREEN_WIDTH)
        return width to height
    }

    private fun resolveRenderSize(
        contentWidth: Int,
        contentHeight: Int,
        target: RenderTarget
    ): RenderSize {
        return when (target) {
            RenderTarget.Full -> RenderSize(contentWidth, contentHeight, 1f)
            is RenderTarget.Thumbnail -> {
                val boundedWidth = target.maxWidthPx.coerceAtLeast(1)
                val boundedHeight = target.maxHeightPx.coerceAtLeast(1)

                val scale = min(
                    1f,
                    min(
                        boundedWidth.toFloat() / contentWidth.toFloat(),
                        boundedHeight.toFloat() / contentHeight.toFloat()
                    )
                )

                val width = (contentWidth * scale).toInt().coerceAtLeast(1)
                val height = (contentHeight * scale).toInt().coerceAtLeast(1)
                RenderSize(width, height, scale)
            }
        }
    }
}


