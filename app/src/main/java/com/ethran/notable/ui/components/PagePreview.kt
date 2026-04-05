package com.ethran.notable.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.io.ThumbnailGeneratorEntryPoint
import dagger.hilt.EntryPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/**
 * Renders a preview image for a page.
 * 
 * Automatically listens to [ThumbnailGenerator] updates to refresh the image
 * when a new thumbnail is generated.
 */
@Composable
fun PagePreview(
    modifier: Modifier = Modifier,
    pageId: String,
    onPreviewMissing: (String) -> Unit = {}
) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current

    if (isPreview) {
        Box(modifier = modifier.background(Color.LightGray))
        return
    }

    // Get the generator via EntryPoint since this is a stateless Composable
    val thumbnailGenerator = remember(context) {
        EntryPoints.get(
            context.applicationContext,
            ThumbnailGeneratorEntryPoint::class.java
        ).thumbnailGenerator()
    }

    // Key to force Coil to reload the image when the thumbnail changes
    var refreshTrigger by remember { mutableLongStateOf(0L) }

    val imgFile = remember(pageId) {
        getThumbnailFile(context, pageId)
    }

    // Listen for updates specifically for this pageId
    LaunchedEffect(pageId) {
        thumbnailGenerator.thumbnailUpdated
            .filter { it == pageId }
            .collect {
                refreshTrigger = System.currentTimeMillis()
            }
    }

    // Check if the file exists initially or when refreshed
    LaunchedEffect(pageId, refreshTrigger) {
        val exists = withContext(Dispatchers.IO) { imgFile.exists() }
        if (!exists) {
            onPreviewMissing(pageId)
        }
    }

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(imgFile)
            // Use the refreshTrigger in the cache key to bypass Coil's cache
            .apply {
                if (refreshTrigger > 0) {
                    memoryCacheKey("${imgFile.absolutePath}_$refreshTrigger")
                    diskCacheKey("${imgFile.absolutePath}_$refreshTrigger")
                }
            }
            .build()
    )

    Image(
        painter = painter,
        contentDescription = "Page Preview",
        contentScale = ContentScale.FillWidth,
        modifier = modifier.background(Color.LightGray)
    )
}
