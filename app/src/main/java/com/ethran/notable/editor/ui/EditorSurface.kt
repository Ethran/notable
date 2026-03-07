package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.DrawCanvas
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import io.shipbook.shipbooksdk.Log

@Composable
fun EditorSurface(
    appRepository: AppRepository,
    state: EditorState, page: PageView, history: History
) {
    val coroutineScope = rememberCoroutineScope()
    Log.i(TAG, "recompose surface")

    AndroidView(
        factory = { ctx ->
            DrawCanvas(
                context = ctx,
                appRepository = appRepository,
                state = state, page = page, history = history,
                coroutineScope = coroutineScope
            ).apply {
                init()
                registerObservers()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}