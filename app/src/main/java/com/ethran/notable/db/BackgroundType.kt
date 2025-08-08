package com.ethran.notable.db

import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log


sealed class BackgroundType(val key: String, val folderName: String) {
    data object Image : BackgroundType("image", "images")
    data object ImageRepeating : BackgroundType("imagerepeating", "images")
    data object CoverImage : BackgroundType("coverImage", "covers")
    data object Native : BackgroundType("native", "")

    // If notebook is of type AutoPdf, its consider Observable.
    // If page is of type AutoPdf, it will follow the page number in notebook.
    data object AutoPdf: BackgroundType("autoPdf", "pdfs")

    // Static page of pdf
    data class Pdf(val page: Int) : BackgroundType("pdf$page", "pdfs")


    companion object {
        fun fromKey(key: String): BackgroundType = when {
            key == Image.key -> Image
            key == ImageRepeating.key -> ImageRepeating
            key == CoverImage.key -> CoverImage
            key == Native.key -> Native
            key == AutoPdf.key -> AutoPdf
            key.startsWith("pdf") && key.removePrefix("pdf").toIntOrNull() != null -> {
                val page = key.removePrefix("pdf").toInt()
                Pdf(page)
            }

            else -> {
                Log.e(TAG, "BackgroundType.fromKey: Unknown key: $key")
                Native
            } // fallback
        }
    }

}