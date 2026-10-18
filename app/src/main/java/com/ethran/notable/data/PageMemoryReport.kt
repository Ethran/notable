package com.ethran.notable.data

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/*
 * Reporting types for [PageDataManager.memorySnapshot], plus the entry point the debug memory view
 * uses to reach the manager.
 *
 * These are immutable value types that hold no reference to live cache state, so once a snapshot is
 * built the caller may read it freely off [PageDataManager]'s lock.
 */

/** One cached page's memory accounting, as seen by [PageDataManager.memorySnapshot]. */
data class PageMemoryRow(
    val pageId: String,
    val strokeCount: Int,
    val pointCount: Long,
    val imageCount: Int,
    /** Counted stroke/image bytes — this is what the entry budget gates on. */
    val sizeBytes: Long,
    /** Pre-load estimate that admitted the page; compare with [sizeBytes] to calibrate. */
    val estimateBytes: Long,
    /** Windowed screen bitmap, if the SoftReference is still alive. Outside the counted budget. */
    val bitmapBytes: Long,
    val backgroundKey: String?,
    val loaded: Boolean,
    val loading: Boolean,
    val pinned: Boolean,
    /** Held with [PageDataManager.hold]. */
    val held: Boolean,
    /** LRU stamp. Lowest among unpinned pages is evicted first. */
    val lastAccessSeq: Long,
)

/** One pooled background bitmap. Deduped by [id], so many pages may share this one cost. */
data class BackgroundMemoryRow(
    val id: String,
    val name: String,
    /** PDF page this was rendered from, or -1 for whole-image backgrounds. */
    val pageNumber: Int,
    val scale: Float,
    val bytes: Long,
    val resident: Boolean,
    /** LRU stamp on the pool's own eviction line, independent of the page LRU. */
    val lastAccessSeq: Long,
)

/**
 * A point-in-time view of where the app's memory has gone, taken atomically under
 * [PageDataManager]'s lock. Read-only; purely for the debug memory view.
 *
 * The three pools [PageDataManager] manages are deliberately kept apart here too — they are evicted
 * by different rules and, more importantly, they don't even live in the same heap:
 * - [entryBytes] / [entryCapBytes] — stroke+image page data, the line that drives page eviction.
 *   **Java heap.**
 * - [backgroundBytes] / [backgroundCapBytes] — the shared background bitmap pool, evicted on its own
 *   line by `trimBackgroundsLocked`. **Native heap** (minSdk 29, so bitmap pixels are always native).
 * - [bitmapBytes] — the per-page windowed screen bitmaps. **Native heap**, and on *no* budget line
 *   at all: they are `SoftReference`s that ART reclaims under pressure.
 *
 * Consequently [entryBytes] is the only counted line inside [usedHeapBytes], and the two bitmap
 * pools are counted inside [nativeHeapBytes]. Mixing them into one "used memory" number hides the
 * pool that is actually large.
 *
 * Counted and measured figures come from different sources and can disagree — see
 * [heapOverAccounted] and [nativeOverAccounted], which say so rather than letting the leftover
 * silently clamp to zero.
 */
data class MemorySnapshot(
    val maxHeapBytes: Long,
    val totalHeapBytes: Long,
    val freeHeapBytes: Long,
    val nativeHeapBytes: Long,
    val heapBudgetFraction: Double,
    val entryCapBytes: Long,
    val entryBytes: Long,
    val backgroundCapBytes: Long,
    val backgroundBytes: Long,
    val heldPageIds: List<String>,
    val pages: List<PageMemoryRow>,
    val backgrounds: List<BackgroundMemoryRow>,
) {
    /** Java heap actually in use right now (`totalMemory - freeMemory`). */
    val usedHeapBytes: Long get() = totalHeapBytes - freeHeapBytes

    /**
     * Windowed screen bitmaps still held by live SoftReferences. Native, and on no budget line —
     * neither the entry cap nor the background cap accounts for these.
     */
    val bitmapBytes: Long get() = pages.sumOf { it.bitmapBytes }

    /** Both native bitmap pools together: what this cache believes it holds off the Java heap. */
    val countedNativeBytes: Long get() = backgroundBytes + bitmapBytes

    /**
     * Java heap not attributable to the page-entry cache: Compose/UI, a load's transient stroke
     * copy, everything else. Deliberately does **not** subtract the bitmap pools — those are native.
     *
     * Clamped at zero; check [heapOverAccounted] before trusting a zero here.
     */
    val untrackedHeapBytes: Long
        get() = (usedHeapBytes - entryBytes).coerceAtLeast(0L)

    /**
     * Native heap not attributable to either bitmap pool: the graphics driver, the Onyx SDK, PDF
     * decoding buffers. A large value here means something outside this cache is holding native
     * memory, so it is worth showing rather than folding away.
     *
     * Clamped at zero; check [nativeOverAccounted] before trusting a zero here.
     */
    val untrackedNativeBytes: Long
        get() = (nativeHeapBytes - countedNativeBytes).coerceAtLeast(0L)

    /**
     * True when the modelled entry bytes exceed the heap actually in use. [entryBytes] is computed
     * by [PageMemoryModel], a formula rather than a measurement, so this means the model
     * over-estimates — and the entry budget is therefore evicting earlier than it needs to.
     */
    val heapOverAccounted: Boolean get() = entryBytes > usedHeapBytes

    /**
     * True when the counted bitmaps exceed the measured native heap. Expected cause: a bitmap
     * allocated as `Config.HARDWARE` or through a graphics buffer, which `Bitmap.allocationByteCount`
     * reports but `Debug.getNativeHeapAllocatedSize` does not. Worth surfacing rather than hiding,
     * because it invalidates the native-heap split below it.
     */
    val nativeOverAccounted: Boolean get() = countedNativeBytes > nativeHeapBytes
}

/** Lets stateless composables (the debug memory view) reach the singleton cache manager. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PageDataManagerEntryPoint {
    fun pageDataManager(): PageDataManager
}
