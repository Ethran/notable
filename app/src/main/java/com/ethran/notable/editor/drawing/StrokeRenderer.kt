package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.drawing.onyx.OnyxStrokeRenderer

/**
 * The dry-ink seam: turns a persisted stroke into pixels. Everything between the DB and a
 * renderer speaks StrokePoint; renderer-specific types (e.g. Onyx TouchPoint) stay inside
 * the implementation.
 */
interface StrokeRenderer {
    fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset)
}

object StrokeRenderers {
    /**
     * The single place the dry-ink renderer is chosen. The Onyx pen wrappers are plain
     * canvas code and run on every device, so this matches pre-seam behavior everywhere.
     * Phase 0.3 replaces this hardcoded choice with the settings-backed backend flag.
     */
    val current: StrokeRenderer = OnyxStrokeRenderer
}
