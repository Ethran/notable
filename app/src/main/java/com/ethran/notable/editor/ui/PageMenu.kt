package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.deletePage
import com.ethran.notable.io.copyLink
import com.ethran.notable.io.pageLinkTitle
import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.utils.onSuccess
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PageMenuEntryPoint {
    fun pageSaver(): PageSaver
}

@Composable
fun PageMenu(
    appRepository: AppRepository,
    notebookId: String? = null,
    pageId: String,
    index: Int? = null,
    canDelete: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSaver = remember(context) {
        EntryPoints.get(context.applicationContext, PageMenuEntryPoint::class.java).pageSaver()
    }
    val snackState = LocalSnackContext.current
    ScaledPopup(
        alignment = Alignment.TopStart,
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max)
        ) {
            if (notebookId != null && index != null) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.bookRepository.changePageIndex(
                                    notebookId,
                                    pageId,
                                    index - 1
                                )
                            }
                        }
                ) {
                    Text("Move Left")
                }

                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.bookRepository.changePageIndex(
                                    notebookId,
                                    pageId,
                                    index + 1
                                )
                            }
                        }) {
                    Text("Move right")
                }
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.newPageInBook(notebookId, index + 1)
                            }
                        }) {
                    Text("Insert after")
                }
            }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            pageSaver.saveAll().onSuccess { appRepository.duplicatePage(pageId) }
                        }
                    }) {
                Text("Duplicate")
            }
            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            val title = pageLinkTitle(appRepository, pageId)
                            copyLink(context, DeepLink.Page(pageId), title, snackState::showOrUpdateSnack)
                            onClose()
                        }
                    }) {
                Text(stringResource(R.string.copy_link))
            }
            if (canDelete) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                deletePage(appRepository, pageId, context.filesDir)
                            }
                        }) {
                    Text("Delete")
                }
            }
        }
    }
}
