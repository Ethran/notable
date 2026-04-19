package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.DrawCanvas
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.SelectionState
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("EditorSurface")

@Composable
fun EditorSurface(
    viewModel: EditorViewModel,
    page: PageView,
    history: History
) {
    val coroutineScope = rememberCoroutineScope()
    log.i("recompose surface")

    AndroidView(
        factory = { ctx ->
            DrawCanvas(
                context = ctx,
                coroutineScope = coroutineScope,
                viewModel = viewModel,
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
