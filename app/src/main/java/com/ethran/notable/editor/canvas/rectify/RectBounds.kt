package com.ethran.notable.editor.canvas.rectify

import android.graphics.Rect

/**
 * An axis-aligned screen rectangle with geometry that runs on the JVM.
 *
 * [Rect] cannot be used in unit tests: the android.jar stubs discard everything, so even
 * `Rect(10, 20, 30, 40).left` comes back 0 and `union`/`intersect` are no-ops. A band-bound test
 * written against [Rect] would therefore assert nothing and pass. This type holds the same four
 * edges with real arithmetic, and converts to [Rect] only at the adapter boundary, on device.
 *
 * Coordinates are screen pixels; a rect is empty when its edges are inverted or touching, matching
 * [Rect.isEmpty] after [sort].
 */
data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /**
     * True when this covers no pixel, matching `Rect.isEmpty`: the rect is empty only when its
     * **width** or its **height** is not positive, each measured from the rect's own edges rather
     * than from sorted ones. So an inverted rect such as (5, 5, 1, 1) is not empty by this
     * definition — [sorted] first if you need the area reading.
     */
    val isEmpty: Boolean
        get() = !(left < right && top < bottom)

    /** Signed, like `Rect.width()`: negative when [left] > [right]. */
    val width: Int get() = right - left

    /** Signed, like `Rect.height()`: negative when [top] > [bottom]. */
    val height: Int get() = bottom - top

    /** Grows every edge out by [inset], as [Rect.inset] does for a negative inset. */
    fun inflate(inset: Int): RectBounds =
        RectBounds(left - inset, top - inset, right + inset, bottom + inset)

    /** The smallest rect covering both, computed on the unsorted edges so a caller may pass one
     *  still to be [sort]ed. */
    fun union(other: RectBounds): RectBounds = RectBounds(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    /**
     * The overlap of this and [other], both taken as [sort]ed areas; null when they miss.
     *
     * [Rect.intersect] narrows in place and reports whether anything is left; returning null keeps
     * the caller from mistaking an inverted, empty result for a region worth pushing.
     */
    fun intersect(other: RectBounds): RectBounds? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return RectBounds(l, t, r, b).takeIf { !it.isEmpty }
    }

    /** Orders the edges so [left] <= [right] and [top] <= [bottom], as [Rect.sort]. */
    fun sorted(): RectBounds = RectBounds(
        minOf(left, right),
        minOf(top, bottom),
        maxOf(left, right),
        maxOf(top, bottom),
    )

    companion object {
        /**
         * Covers nothing, sitting at the origin — what `Rect()` gives and what production starts a
         * band from. [union] of a real rect with this yields the real rect's bounds plus the
         * origin, so a first band is built rather than unioned.
         */
        val EMPTY = RectBounds(0, 0, 0, 0)

        /** The whole area from the origin to ([width], [height]). */
        fun ofSize(width: Int, height: Int): RectBounds = RectBounds(0, 0, width, height)
    }
}

fun Rect.toRectBounds(): RectBounds = RectBounds(left, top, right, bottom)

fun RectBounds.toRect(): Rect = Rect(left, top, right, bottom)
