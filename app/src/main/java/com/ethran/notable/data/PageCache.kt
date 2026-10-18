package com.ethran.notable.data

/**
 * Pure, Android-free cache-sizing and eviction policy for [PageDataManager].
 *
 * Split out so the budget arithmetic and eviction ordering are unit-testable without a device.
 * Nothing here touches Room, Bitmaps or coroutines; [PageDataManager] owns the mutable cache and
 * calls into these helpers under its lock.
 */

/**
 * Approximate **resident** byte cost of the in-memory page model. These are deliberate
 * over-estimates of the boxed model so the budget errs toward evicting rather than OOMing:
 *
 * - A [com.ethran.notable.data.db.StrokePoint] is a data class whose optional fields
 *   (pressure/tiltX/tiltY/dt) are boxed; a typical Onyx point is an object header plus several
 *   boxed wrappers, ~100 B. We charge [BYTES_PER_POINT] per point.
 * - Each [com.ethran.notable.data.db.Stroke] adds an object, its bounding box, id string and list
 *   header — [BYTES_PER_STROKE].
 * - An [com.ethran.notable.data.db.Image] row is metadata only (the bitmap is windowed separately),
 *   so [BYTES_PER_IMAGE] is small.
 *
 * These are counting constants, not a claim of exact heap layout; calibrate against the
 * estimate/actual telemetry (see [PageDataManager] admission logging).
 */
object PageMemoryModel {
    const val BYTES_PER_STROKE = 160L
    const val BYTES_PER_POINT = 96L
    const val BYTES_PER_IMAGE = 256L

    /**
     * Resident bytes for a loaded page, from cheap counts (all O(1)/O(strokes), never O(points)).
     * [totalPointCount] is the summed `points.size` across the page's strokes.
     */
    fun entryBytes(strokeCount: Int, totalPointCount: Long, imageCount: Int): Long =
        strokeCount * BYTES_PER_STROKE +
            totalPointCount * BYTES_PER_POINT +
            imageCount * BYTES_PER_IMAGE

    /**
     * Expansion constant K mapping a page's compressed on-disk blob size (`SUM(LENGTH(points))`,
     * LZ4 + polyline) to its resident cost. Conservative (over-estimate) so admission gates before
     * the heap fills. Refine from the on-device estimate/actual log.
     */
    const val BLOB_EXPANSION_K = 20L

    /** Pre-load resident estimate for admission, from the summed stroke-blob bytes of a page. */
    fun estimateResidentBytes(sumBlobBytes: Long): Long = sumBlobBytes * BLOB_EXPANSION_K
}

/**
 * The heap budget for the whole page cache: a fraction of the app's ART heap ceiling
 * (`Runtime.maxMemory()`), minus a fixed reserve for windowed/UI bitmaps that live outside the
 * counted budget. Bounded against the *app heap*, never `maxMemory - totalMemory` (that is
 * GC-timing dependent and was proven the wrong signal by the reproduced OOM).
 *
 * [maxHeapBytes] is injected so tests can pin a heap size. [fraction] and [reserveBytes] have no
 * defaults on purpose: the policy in force is the one the caller passes, and a default here would
 * be the first thing a reader finds and the last thing that governs.
 */
class CacheBudget(
    private val maxHeapBytes: () -> Long,
    private val fraction: Double,
    private val reserveBytes: Long,
) {
    fun capBytes(): Long =
        ((maxHeapBytes() * fraction).toLong() - reserveBytes).coerceAtLeast(0L)
}

/** One cached page's contribution to eviction decisions. */
data class EvictCandidate(
    val pageId: String,
    val bytes: Long,
    val pinned: Boolean,
    val seq: Long,
)

/**
 * Choose which pages to evict so that `currentResident - evicted + incoming <= cap`.
 *
 * Evicts only **unpinned** pages (pinned = a leased page or a page with an active load), least
 * recently used first (smallest [EvictCandidate.seq]). Stops as soon as the budget fits, or when no
 * unpinned candidates remain — in which case it returns *every* unpinned page and the caller loads
 * anyway (a shown page is never refused). Pure: no side effects, order is deterministic.
 *
 * Note (intended): a victim's exclusive pooled background is freed by the caller when the page is
 * removed, but that byte credit is NOT modeled here — [EvictCandidate.bytes] is stroke/image bytes
 * only. The effect is at worst a hair of over-eviction, which is the safe direction for an OOM fix.
 */
fun selectEvictions(
    candidates: List<EvictCandidate>,
    currentResident: Long,
    incoming: Long,
    cap: Long,
): List<String> {
    if (currentResident + incoming <= cap) return emptyList()
    val victims = candidates.asSequence().filter { !it.pinned }.sortedBy { it.seq }
    val result = ArrayList<String>()
    var resident = currentResident
    for (v in victims) {
        if (resident + incoming <= cap) break
        result.add(v.pageId)
        resident -= v.bytes
    }
    return result
}

/** Outcome of an admission check for a page about to be loaded. */
sealed interface AdmissionDecision {
    /** Do not load (a prefetch that doesn't fit, or whose cost is unknown). */
    data object Skip : AdmissionDecision

    /** Load after evicting [evict]; [overBudget] true if it still won't fit (a shown page only). */
    data class Load(val evict: List<String>, val overBudget: Boolean) : AdmissionDecision
}

/**
 * Pure admission policy.
 *
 * - A **shown page** ([isPrefetch] = false) is never refused: it evicts unpinned LRU pages to
 *   fit and, if it still doesn't fit, loads anyway with [AdmissionDecision.Load.overBudget] set. On
 *   an unknown cost ([estimateBytes] null) it fails **open** — loads with a zero gating cost.
 * - A **prefetch** ([isPrefetch] = true) is opportunistic: it loads only into spare budget and
 *   never evicts, and it fails **closed** — an unknown cost ([estimateBytes] null) means Skip.
 *
 * [alreadyResidentBytes] is what this page already holds in the cache (strokes drawn during load, or
 * a partial re-load); it is subtracted from the estimate so a partially-resident page isn't
 * double-counted against the budget.
 */
fun decideAdmission(
    isPrefetch: Boolean,
    estimateBytes: Long?,
    alreadyResidentBytes: Long,
    residentBytes: Long,
    cap: Long,
    candidates: List<EvictCandidate>,
): AdmissionDecision {
    val incremental: Long? = estimateBytes?.let { (it - alreadyResidentBytes).coerceAtLeast(0L) }

    if (isPrefetch) {
        if (incremental == null) return AdmissionDecision.Skip // fail closed on unknown cost
        return if (residentBytes + incremental <= cap) AdmissionDecision.Load(emptyList(), false)
        else AdmissionDecision.Skip
    }

    val inc = incremental ?: 0L // a shown page fails open
    val evict = selectEvictions(candidates, residentBytes, inc, cap)
    val evicted = evict.toHashSet()
    val freed = candidates.asSequence().filter { it.pageId in evicted }.sumOf { it.bytes }
    val overBudget = (residentBytes - freed) + inc > cap
    return AdmissionDecision.Load(evict, overBudget)
}
