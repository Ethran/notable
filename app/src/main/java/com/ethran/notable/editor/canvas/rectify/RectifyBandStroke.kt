package com.ethran.notable.editor.canvas.rectify

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.rawInputMaxPressure
import java.util.Date

/**
 * The band as the stroke it is about to become, in page coordinates, so the dry renderer can paint it.
 *
 * Describing the band the way `handleDraw` will describe the stroke — and letting
 * `OnyxStrokeRenderer` paint both — is what keeps panel and page from drifting apart.
 *
 * Coordinates are page space, the space the dry render draws in. The caller supplies the page
 * transform (`scale(zoom)` then translate by the scroll), so the nib stays unzoomed here.
 *
 * @param spec the gesture's paint rule: `strokeWidth` is the nib in page units, `strokeWidthPx` is
 *   that width on screen, and `pressure` is on the digitizer's raw scale.
 * @param scroll the page's scroll, added so the points land where the stored stroke keeps them.
 * @param zoom the page's zoom, which maps the screen-space endpoints into page space.
 */
internal fun rectifyBandStroke(
    spec: BandPaintSpec,
    a: Point2D,
    b: Point2D,
    scroll: Offset,
    zoom: Float,
    timestampMs: Long = 0L,
): Stroke {
    val page = zoom.coerceAtLeast(1f)
    // The band is stamped one texture bitmap per point, so the point set is the texture.
    val strokePoints = bandStrokePoints(
        a = a,
        b = b,
        scroll = scroll,
        zoom = page,
        pressure = spec.pressure,
    )

    // The two endpoints enclose the whole line, so the bounds are their min and max.
    val left = minOf(a.x, b.x) / page + scroll.x
    val right = maxOf(a.x, b.x) / page + scroll.x
    val top = minOf(a.y, b.y) / page + scroll.y
    val bottom = maxOf(a.y, b.y) / page + scroll.y

    return Stroke(
        // The nib, unscaled — the same value handleDraw stores as Stroke.size.
        size = spec.strokeWidth,
        pen = spec.pen,
        color = spec.color,
        maxPressure = MAX_PRESSURE_NORMALIZED,
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        points = strokePoints,
        pageId = "",
        createdAt = Date(timestampMs),
        updatedAt = Date(timestampMs),
    )
}

/**
 * The band's points in page coordinates: the same [LINE_POINTS] evenly spread from A to B that
 * `transformToLine` builds for the committed stroke.
 *
 * This mirroring is the point of the whole function: `NeoCharcoalPenWrapper` blits one texture bitmap
 * per point, so the point set *is* the texture.
 *
 * [pressure] goes on every point rather than being interpolated from A to B, matching the
 * constant-pressure commit.
 *
 * @param a [b] the band's endpoints, in screen pixels.
 * @param scroll the page's scroll, added so the points land where the stored stroke keeps them.
 * @param zoom screen-to-page divisor, matching `TouchPoint.toStrokePoint`.
 */
internal fun bandStrokePoints(
    a: Point2D,
    b: Point2D,
    scroll: Offset,
    zoom: Float,
    pressure: Float,
): List<StrokePoint> {
    val aPage = a.toPageStrokePoint(scroll, zoom)
    val bPage = b.toPageStrokePoint(scroll, zoom)
    val normalised = (pressure / rawInputMaxPressure).coerceIn(0f, 1f)

    return List(LINE_POINTS) { i ->
        val fraction = i.toFloat() / (LINE_POINTS - 1)
        StrokePoint(
            x = aPage.x + ((bPage.x - aPage.x) * fraction),
            y = aPage.y + ((bPage.y - aPage.y) * fraction),
            pressure = normalised,
            tiltX = aPage.tiltX,
            tiltY = aPage.tiltY,
        )
    }
}

/** How many points a rectified line is drawn from — `transformToLine`'s count, kept in step. */
private const val LINE_POINTS = 100
