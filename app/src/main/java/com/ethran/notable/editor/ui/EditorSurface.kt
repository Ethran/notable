package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.DrawCanvas
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("EditorSurface")

@Composable
fun EditorSurface(
    appRepository: AppRepository,
    viewModel: EditorViewModel, page: PageView, history: History
) {
    val coroutineScope = rememberCoroutineScope()
    log.i("recompose surface")

    // Create EditorState wrapper for backward compatibility with DrawCanvas
    val editorState = EditorState(viewModel)

    AndroidView(
        factory = { ctx ->
            DrawCanvas(
                context = ctx,
                appRepository = appRepository,
                coroutineScope = coroutineScope,
                state = editorState,
                page = page,
                history = history
            ).apply {
                init()
                registerObservers()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}