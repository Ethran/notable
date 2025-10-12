package com.ethran.notable.data.model

import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.ui.SnackState.Companion.logAndShowError
import io.shipbook.shipbooksdk.Log

sealed class BackgroundType(val key: String, val folderName: String) {
    data object Image : BackgroundType("image", "images")
    data object ImageRepeating : BackgroundType("imagerepeating", "images")
    data object CoverImage : BackgroundType("coverImage", "covers")
    data object Native : BackgroundType("native", "")

    // If notebook is of type AutoPdf, its consider Observable.
    // If page is of type AutoPdf, it will follow the page number in notebook.
    data object AutoPdf : BackgroundType("autoPdf", "pdfs")

    // Static page of pdf
    data class Pdf(val page: Int) : BackgroundType("pdf$page", "pdfs")

    fun AutoPdf.getPage(appRepository: AppRepository, bookId: String?, pageId: String): Int? {
        if (bookId == null) return 0
        return try {
            appRepository.getPageNumber(bookId, pageId)
        } catch (e: Exception) {
            logAndShowError(
                "PageView.currentPageNumber", "Error getting page number: ${e.message}"
            )
            null
        }
    }
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

    fun resolveForExport(currentPage: Int?): BackgroundType =
        when (this) {
            AutoPdf ->
                if (currentPage == null) {
                    Log.e(TAG, "BackgroundType.resolveForExport: missing currentPage for AutoPdf")
                    Native
                } else {
                    Pdf(currentPage)
                }

            else -> this
        }
}