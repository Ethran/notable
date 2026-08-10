package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.Pane
import com.ethran.notable.editor.PaneGroup
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.DrawCanvas
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.SelectionState
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("EditorSurface")

@Composable
fun EditorSurface(
    viewModel: EditorViewModel,
    paneGroup: PaneGroup,
    /**
     * The panes that should be on screen.
     *
     * Applied to [paneGroup] here rather than by the caller so that the group is always current
     * before the canvas reconciles against it. Driving it from a `LaunchedEffect` instead would
     * race: effects run after composition is applied, but this update block runs during, so the
     * canvas could sync against the previous pane set and then never be asked again.
     */
    panes: List<Pane>,
) {
    val coroutineScope = rememberCoroutineScope()
    log.i("recompose surface")

    AndroidView(
        factory = { ctx ->
            DrawCanvas(
                context = ctx,
                coroutineScope = coroutineScope,
                viewModel = viewModel,
                paneGroup = paneGroup,
            ).apply {
                init()
                registerObservers()
            }
        },
        // The canvas is built once and never rebuilt, because rebuilding it destroys the
        // SurfaceView — a device trace showed the replacement surface taking six seconds to be
        // allocated, with the screen blank until a rotation forced it. Splitting therefore mutates
        // the PaneGroup in place and this reconciles the canvas to it. syncPanes is idempotent, so
        // running on every recomposition costs a set comparison.
        update = { canvas ->
            paneGroup.updatePanes(panes)
            canvas.syncPanes()
        },
        modifier = Modifier.fillMaxSize()
    )
}
