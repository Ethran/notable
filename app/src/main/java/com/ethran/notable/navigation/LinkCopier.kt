package com.ethran.notable.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Puts a link to a page or notebook on the clipboard, in the format chosen in settings. */
class LinkCopier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val snackDispatcher: SnackDispatcher,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** Runs on the application scope, so closing the menu that asked for it doesn't cancel it. */
    fun copy(link: DeepLink) = applicationScope.launch {
        val text = link.toMarkup(title(link), GlobalAppSettings.current.linkFormat)
        withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Notable link", text))
        }
        // From Android 13 the system confirms a copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            snackDispatcher.showOrUpdateSnack(SnackConf(text = "Link copied", duration = 2000))
    }

    private suspend fun title(link: DeepLink): String = withContext(Dispatchers.IO) {
        when (link) {
            is DeepLink.Page -> {
                val book = appRepository.pageRepository.getById(link.pageId)?.notebookId
                    ?.let { appRepository.bookRepository.getById(it) }
                val index = book?.pageIds?.indexOf(link.pageId) ?: -1
                if (book != null && index >= 0) "${book.title}, p. ${index + 1}" else "Notable page"
            }

            is DeepLink.Book -> appRepository.bookRepository.getById(link.bookId)?.title
                ?: "Notable notebook"

            is DeepLink.Pdf -> link.path.substringAfterLast('/')
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LinkCopierEntryPoint {
    fun linkCopier(): LinkCopier
}

/** [LinkCopier] for composables outside a Hilt view model. */
@Composable
fun rememberLinkCopier(): LinkCopier {
    val context = LocalContext.current
    return remember(context) {
        EntryPoints.get(context.applicationContext, LinkCopierEntryPoint::class.java).linkCopier()
    }
}
