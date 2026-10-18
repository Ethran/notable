package com.ethran.notable.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.BuildConfig
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.BackgroundType.AutoPdf.getPage
import com.ethran.notable.data.model.BackgroundType.CoverImage
import com.ethran.notable.data.model.BackgroundType.ImageRepeating
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.saveHQPagePreview
import com.ethran.notable.editor.utils.savePageThumbnail
import com.ethran.notable.io.loadBackgroundBitmap
import com.ethran.notable.utils.chunked
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max


// Save bitmap, to avoid loading from disk every time.
data class CachedBackground(val path: String, val pageNumber: Int, val scale: Float) {
    val id: String = keyOf(path, pageNumber)

    var bitmap: Bitmap? = loadBackgroundBitmap(path, pageNumber, scale)

    // LRU stamp for the background pool's own eviction line; bumped when set or read.
    var lastAccessSeq: Long = 0L

    fun bitmapBytes(): Long = bitmap?.allocationByteCount?.toLong() ?: 0L

    fun matches(filePath: String, pageNum: Int, targetScale: Float): Boolean {
        return path == filePath && pageNumber == pageNum && scale >= targetScale // Consider valid if our scale is larger
    }

    companion object {
        fun keyOf(path: String, pageNumber: Int): String {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest("$path#$pageNumber".toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * All in-memory state for a single cached page, owned by [PageDataManager] behind its single
 * [PageDataManager.lock]. One object per page, so a page's fields can't disagree with each other.
 *
 * Nullable collections: null == not loaded, empty list == loaded-but-empty.
 */
internal class PageCacheEntry(val pageId: String) {
    // Replaced on every change, never modified in place: readers keep the list they got without
    // holding the lock.
    var strokes: List<Stroke>? = null
    var images: List<Image>? = null

    // Resident bytes of strokes+images only (backgrounds and windowed bitmaps excluded).
    var sizeBytes: Long = 0L
    var sizeComputed: Boolean = false

    // Pre-load estimate that admitted this page; kept for the estimate/actual calibration log.
    var estimateBytes: Long = 0L

    var loadJob: Job? = null
    var backgroundKey: String? = null

    // Native (dotted/lined/blank) backgrounds have no bitmap; caching this lets
    // ensureBackgroundLoaded skip a pointless reload. null = not yet known.
    var backgroundIsNative: Boolean? = null

    // Windowed screen bitmap; SoftReference so ART can reclaim it under pressure.
    var bitmap: SoftReference<Bitmap>? = null

    // LRU stamp; bumped on genuine access.
    var lastAccessSeq: Long = 0L

    val loaded: Boolean get() = strokes != null && images != null && sizeComputed
}

/**
 * Changes to one page that are not in the database yet: the latest version of each changed stroke
 * and image by id, or null for one that was removed. Only the outcome per id is kept, so edits that
 * touch the same ids repeatedly — a page cut and its undo, a move — reduce to their result, and
 * writing them doesn't depend on the order the edits were made in.
 *
 * Guarded by [PageDataManager]'s lock while it collects edits. Once handed to [PageSaver] by
 * [PageDataManager.takeUnsaved] it is only read, until [PageDataManager.finishSaving].
 */
internal class PageChanges {
    val strokes = HashMap<String, Stroke?>()
    val images = HashMap<String, Image?>()

    /** Scroll to persist, or null if unchanged. Not an edit: it doesn't advance edit timestamps. */
    var scrollY: Int? = null

    /** Whether strokes or images changed, not only the scroll. */
    val hasEdits: Boolean get() = strokes.isNotEmpty() || images.isNotEmpty()

    /** Adds [newer] on top of these changes; where both changed an id, [newer] wins. */
    fun putAll(newer: PageChanges) {
        strokes.putAll(newer.strokes)
        images.putAll(newer.images)
        newer.scrollY?.let { scrollY = it }
    }
}

// Cache manager companion object
@Singleton
class PageDataManager @Inject constructor(
    private val appRepository: AppRepository,
    private val appEventBus: AppEventBus,
    private val backgroundFileWatcher: BackgroundFileWatcher,
    private val viewport: PageViewportState,
) {
    val log = ShipBook.getLogger("PageDataManager")
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Id of the page in front, set by [setPage]: it is never evicted, keeps its background when the
    // pool is trimmed, and is repainted when its background file changes. Empty when none is.
    // Read from every thread; @Volatile so a stale read can't unpin it mid-evict.
    @Volatile
    private var currentPage: String = ""

    /**
     * Single lock for all cached page state ([entries], [entriesTotalBytes], [backgroundCache],
     * per-entry fields, and the unsaved edits in [unsaved] and [saving]). A plain monitor (not a
     * suspending Mutex) so hot non-suspend accessors (drawing, selection) can take it.
     *
     * **Invariant: nothing blocking or re-entrant runs inside `synchronized(lock)`.**
     * The drawing thread blocks on this monitor, so anything slow or re-entrant held across it is a
     * jank source or a deadlock waiting to happen. Inside the lock there must be no:
     * - suspend calls — DB reads and cost estimation run outside, only their results are stored;
     * - filesystem or syscall work — see [BackgroundFileWatcher], called with the lock released;
     * - Compose snapshot commits — they run Compose's global write observers and can wake the
     *   recomposer. Height/scroll/zoom live in [PageViewportState] for exactly this reason, and
     *   this class no longer touches snapshot state at all;
     * - other locks;
     * - remote logging above `log.d` — `log.w`/`log.e`/`log.i` reach ShipBook. Decide under the
     *   lock, report after; see the over-budget path in [getOrStartLoadingJob] and
     *   [validatePageDataLoaded], which classifies under the lock and repairs outside it.
     *
     * What *is* allowed is appending to a buffered flow: [AppEventBus.tryEmit],
     * [PageViewportState.scheduleRemoval] and [BackgroundFileWatcher.unwatchAsync] all enqueue and
     * return, delivering nothing inline, so they never block the holder or re-enter this class.
     * Eviction relies on that — it happens under the lock and has to hand work to both collaborators.
     * The one launch under the lock ([getOrStartLoadingJob]) is justified at its call site.
     */
    private val lock = Any()

    // Insertion-ordered for stable iteration/logging; LRU is driven by [PageCacheEntry.lastAccessSeq].
    private val entries = LinkedHashMap<String, PageCacheEntry>()

    // Running total; invariant (enforced by construction + [assertTotalsLocked]):
    //   entriesTotalBytes == entries.values.sumOf { it.sizeBytes }
    private var entriesTotalBytes = 0L
    private var accessSeq = 0L

    // Shared background pool, deduped by CachedBackground.id. Large PDF/image bitmaps managed on
    // their OWN budget line ([trimBackgroundsLocked]), separate from stroke/image page eviction, so
    // a page full of cheap strokes is never evicted just because backgrounds are big.
    private val backgroundCache = LinkedHashMap<String, CachedBackground>()
    private var bgAccessSeq = 0L

    // Edits not in the database yet, per page: [unsaved] collects new ones, [saving] holds the ones
    // [PageSaver] is writing. Kept apart from [entries] so evicting a page never drops its edits; a
    // page loaded again gets them laid over its rows (see [mergeLoadedLocked]). Guarded by [lock].
    private val unsaved = HashMap<String, PageChanges>()
    private val saving = HashMap<String, PageChanges>()

    // Wakes [PageSaver] when [unsaved] gains changes. Conflated: one pending signal is enough.
    private val unsavedSignal = Channel<Unit>(Channel.CONFLATED)
    internal val unsavedChanges: ReceiveChannel<Unit> get() = unsavedSignal

    // Fraction of the ART app-heap that stroke pages + backgrounds may use together. The rest is
    // left uncounted for the windowed bitmap, Compose/UI objects, and a load's transient stroke
    // copy. Tune down first if the app OOMs.
    private val heapBudgetFraction = 0.7

    // Stroke/image page budget. Governs entry eviction only — backgrounds aren't counted against it,
    // so a pathologically large page may use the whole ceiling, evicting backgrounds first.
    private val budget = CacheBudget(
        { Runtime.getRuntime().maxMemory() }, fraction = heapBudgetFraction, reserveBytes = 0L
    )

    // ARGB_8888; backgrounds and windowed bitmaps are both 4 bytes per pixel.
    private val bytesPerPixel = 4

    // Ceiling on the background floor below, as a fraction of the ART heap ceiling, so one
    // pathologically large background cannot justify keeping the whole pool resident.
    private val maxBackgroundFloorFraction = 0.25

    // Background files are watched by [BackgroundFileWatcher], which owns that registry and its own
    // lock. It is called only OUTSIDE [lock] (it touches the filesystem) and never calls back in;
    // it reports page ids to invalidate through a flow, collected in [init].

    // Per-page height/scroll/zoom are Compose snapshot state owned by [PageViewportState]; this
    // class only forwards to it, always with [lock] released. The one exception is its documented
    // lock-safe [PageViewportState.scheduleRemoval], used when evicting — which clears all three,
    // so this view state still dies with its page exactly as it did when zoom lived in the entry.

    val dataLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val saveTopic = MutableSharedFlow<String>()

    init {
        collectBackgroundFileChanges()
    }

    /* ---------------- entry helpers (must hold [lock]) ---------------- */

    private fun getOrCreateEntryLocked(pageId: String): PageCacheEntry =
        entries.getOrPut(pageId) { PageCacheEntry(pageId) }

    private fun touchLocked(entry: PageCacheEntry) {
        entry.lastAccessSeq = ++accessSeq
    }

    /** A page is pinned (never evicted) while it is the current page or has an active load. */
    private fun isPinnedLocked(pageId: String, entry: PageCacheEntry): Boolean =
        pageId == currentPage || entry.loadJob?.isActive == true

    /** Distinct background bitmaps, counted once (dedup pool) — the background budget line. */
    private fun backgroundBytesLocked(): Long =
        backgroundCache.values.sumOf { it.bitmapBytes() }

    /** Total resident bytes for reporting only (stroke/image entries + pooled backgrounds). */
    private fun residentBytesLocked(): Long = entriesTotalBytes + backgroundBytesLocked()

    /**
     * Bytes for one screen-sized ARGB_8888 bitmap. Read fresh rather than captured in a field:
     * [SCREEN_WIDTH]/[SCREEN_HEIGHT] are globals that MainActivity overwrites with real display
     * metrics, which can happen after this singleton is constructed — a captured value could be the
     * pre-init EpdController guess.
     */
    private fun screenBitmapBytes(): Long =
        SCREEN_WIDTH.toLong() * SCREEN_HEIGHT * bytesPerPixel

    /**
     * Floor for [backgroundCapLocked]: room for the current page's background plus one neighbour's,
     * so an ordinary page turn doesn't re-decode a PDF page.
     *
     * Measured from the backgrounds actually resident rather than assumed from the screen. A
     * background is rendered at screen width and the source PDF's own aspect ratio, so it is
     * routinely taller than the screen — on a 2560-wide panel a single page measured ~42 MB against
     * a 17.6 MB screen bitmap. A fixed floor can be smaller than one background: the cap would pin
     * to it, every non-current background would be evicted on sight, and each page turn would pay a
     * fresh render. Bounded by [maxBackgroundFloorFraction]. Must hold [lock].
     */
    private fun minBackgroundBytesLocked(): Long {
        val largestResident = backgroundCache.values.maxOfOrNull { it.bitmapBytes() } ?: 0L
        val floor = maxOf(largestResident, screenBitmapBytes()) * 2
        val bound = (Runtime.getRuntime().maxMemory() * maxBackgroundFloorFraction).toLong()
        return floor.coerceAtMost(bound)
    }

    /**
     * Byte budget for the background pool: heap left under the ceiling after resident stroke/image
     * bytes, floored at [minBackgroundBytesLocked]. Strokes (resident + [pendingEntryBytes] of a
     * page about to load) are weighted ×2 so a growing stroke page yields background heap ahead of
     * its own load's transient copy.
     */
    private fun backgroundCapLocked(pendingEntryBytes: Long = 0L): Long {
        val ceiling = (Runtime.getRuntime().maxMemory() * heapBudgetFraction).toLong()
        return (ceiling - 2 * entriesTotalBytes - 2 * pendingEntryBytes)
            .coerceAtLeast(minBackgroundBytesLocked())
    }

    /**
     * Evict least-recently-used pooled backgrounds until the pool fits [backgroundCapLocked]. The
     * current page's background is pinned (never evicted). An evicted background leaves its page's
     * [PageCacheEntry.backgroundKey] intact and is transparently reloaded on demand by
     * [ensureBackgroundLoaded] when that page is next shown — so this is safe for still-resident
     * pages. Must hold [lock].
     */
    private fun trimBackgroundsLocked(pendingEntryBytes: Long = 0L) {
        val cap = backgroundCapLocked(pendingEntryBytes)
        var total = backgroundBytesLocked()
        if (total <= cap) return
        val currentKey = entries[currentPage]?.backgroundKey
        val victims = backgroundCache.values
            .asSequence()
            .filter { it.id != currentKey }
            .sortedBy { it.lastAccessSeq }
            .toList()
        var evicted = 0
        for (bg in victims) {
            if (total <= cap) break
            backgroundCache.remove(bg.id)
            total -= bg.bitmapBytes()
            evicted++
        }
        if (evicted > 0)
            log.d("trimBackgrounds evicted $evicted background(s) (cap=${cap / 1024 / 1024}MB, now=${total / 1024 / 1024}MB)")
    }

    /**
     * Ensures [pageId]'s background bitmap is resident, reloading it if the pool evicted it while the
     * page's strokes stayed cached. No-op if already present (just bumps its LRU stamp).
     */
    private suspend fun ensureBackgroundLoaded(pageId: String) {
        if (pageId.isEmpty()) return
        val needsReload = synchronized(lock) {
            val entry = entries[pageId]
            when {
                // Native (dotted/lined/blank) pages have no bitmap background — nothing to reload.
                entry?.backgroundIsNative == true -> false
                else -> {
                    val bg = entry?.backgroundKey?.let { backgroundCache[it] }
                    if (bg?.bitmap != null) {
                        bg.lastAccessSeq = ++bgAccessSeq
                        false
                    } else true
                }
            }
        }
        if (needsReload) {
            log.d("Background not resident for $pageId — reloading")
            preLoadBackground(pageId)
        }
    }

    /**
     * Recompute an entry's stroke/image resident size and apply the delta to [entriesTotalBytes].
     * O(#strokes), same order as the list copies already done on edit.
     */
    private fun recomputeEntrySizeLocked(entry: PageCacheEntry) {
        val strokeList = entry.strokes
        val imageList = entry.images
        val strokeCount = strokeList?.size ?: 0
        val pointCount = strokeList?.sumOf { it.points.size.toLong() } ?: 0L
        val imageCount = imageList?.size ?: 0
        val newSize = PageMemoryModel.entryBytes(strokeCount, pointCount, imageCount)
        entriesTotalBytes += newSize - entry.sizeBytes
        entry.sizeBytes = newSize
        entry.sizeComputed = true
        assertTotalsLocked()
    }

    private fun assertTotalsLocked() {
        if (!BuildConfig.DEBUG) return
        val sum = entries.values.sumOf { it.sizeBytes }
        if (sum != entriesTotalBytes)
            log.e("Cache size accounting drift: total=$entriesTotalBytes but sum(entries)=$sum")
    }

    private fun evictCandidatesLocked(): List<EvictCandidate> =
        entries.map { (id, e) -> EvictCandidate(id, e.sizeBytes, isPinnedLocked(id, e), e.lastAccessSeq) }

    /* ---------------- memory reporting ---------------- */

    /**
     * A consistent snapshot of the cache's memory accounting, for the debug memory view. Taken under
     * [lock] so the budget lines and the per-page rows always agree with each other; everything is
     * copied into immutable value types so the caller holds no live cache references.
     *
     * O(#resident strokes) — [PageMemoryRow.pointCount] sums each stroke's point list, which is the
     * figure [PageMemoryModel] is calibrated against. That is enough work, under a lock the drawing
     * path also takes, that callers must sample off the main thread.
     */
    fun memorySnapshot(): MemorySnapshot = synchronized(lock) {
        val runtime = Runtime.getRuntime()
        val currentId = currentPage
        val pages = entries.map { (id, e) ->
            PageMemoryRow(
                pageId = id,
                strokeCount = e.strokes?.size ?: 0,
                pointCount = e.strokes?.sumOf { it.points.size.toLong() } ?: 0L,
                imageCount = e.images?.size ?: 0,
                sizeBytes = e.sizeBytes,
                estimateBytes = e.estimateBytes,
                bitmapBytes = e.bitmap?.get()?.allocationByteCount?.toLong() ?: 0L,
                backgroundKey = e.backgroundKey,
                loaded = e.loaded,
                loading = e.loadJob?.isActive == true,
                pinned = isPinnedLocked(id, e),
                isCurrent = id == currentId,
                lastAccessSeq = e.lastAccessSeq,
            )
        }.sortedByDescending { it.sizeBytes }

        val backgrounds = backgroundCache.values.map { bg ->
            BackgroundMemoryRow(
                id = bg.id,
                name = File(bg.path).name,
                pageNumber = bg.pageNumber,
                scale = bg.scale,
                bytes = bg.bitmapBytes(),
                resident = bg.bitmap != null,
                lastAccessSeq = bg.lastAccessSeq,
            )
        }.sortedByDescending { it.bytes }

        MemorySnapshot(
            maxHeapBytes = runtime.maxMemory(),
            totalHeapBytes = runtime.totalMemory(),
            freeHeapBytes = runtime.freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            heapBudgetFraction = heapBudgetFraction,
            entryCapBytes = budget.capBytes(),
            entryBytes = entriesTotalBytes,
            backgroundCapBytes = backgroundCapLocked(),
            backgroundBytes = backgroundBytesLocked(),
            currentPageId = currentId.ifEmpty { null },
            pages = pages,
            backgrounds = backgrounds,
        )
    }

    /* ---------------- loading ---------------- */

    /**
     * Returns the existing loading Job for the page, or starts and returns a new one. Locking is
     * handled internally; suspend work (the cost estimate) runs outside the lock.
     *
     * [isPrefetch] pages are admitted only into spare budget and never evict. The current page
     * ([isPrefetch] = false) is never refused: it evicts unpinned pages to fit, and loads anyway
     * (emitting an over-budget telemetry event) if it still doesn't fit.
     */
    private suspend fun getOrStartLoadingJob(pageId: String, isPrefetch: Boolean): Job? {
        if (pageId.isEmpty()) {
            log.e("Page id is empty")
            logCallStack("PageRepository.getById")
            return null
        }

        // Fast path: an active or already-loaded job needs no DB work.
        synchronized(lock) {
            val e = entries[pageId]
            val job = e?.loadJob
            if (job?.isActive == true) return job
            if (job?.isCompleted == true && e.loaded) return job
        }

        // Estimate the page's resident cost from its compressed blob size (suspend DB) — no lock.
        // null means the estimate failed: the current page then fails open (loads anyway) while a
        // prefetch fails closed (is skipped) — see [decideAdmission].
        val estimate: Long? = try {
            estimatePageCostBytes(pageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Cost estimate failed for $pageId: ${e.message}")
            null
        }

        var result: Job? = null
        // Recorded under the lock, reported after it: an admission report is telemetry, and
        // tryEmit + remote logging have no business running with the drawing lock held.
        var overBudgetCap: Long? = null
        synchronized(lock) {
            val existing = entries[pageId]?.loadJob
            val loaded = entries[pageId]?.loaded == true
            when {
                existing?.isActive == true -> result = existing
                existing?.isCompleted == true && loaded -> result = existing
                else -> {
                    val cap = budget.capBytes()
                    // Subtract what this page already holds so a partially-resident page (strokes
                    // drawn during load / partial re-load) isn't double-counted.
                    val alreadyResident = entries[pageId]?.sizeBytes ?: 0L
                    val decision = decideAdmission(
                        isPrefetch = isPrefetch,
                        estimateBytes = estimate,
                        alreadyResidentBytes = alreadyResident,
                        // Page eviction is gated on stroke/image bytes only; backgrounds have their
                        // own budget line ([trimBackgroundsLocked]) and never evict a stroke page.
                        residentBytes = entriesTotalBytes,
                        cap = cap,
                        candidates = evictCandidatesLocked(),
                    )
                    when (decision) {
                        AdmissionDecision.Skip -> {
                            log.d("Load of $pageId skipped (prefetch, no spare budget / unknown cost; cap=$cap)")
                        }

                        is AdmissionDecision.Load -> {
                            decision.evict.forEach { removePageLocked(it) }
                            // Shrink the background pool to leave heap for this page's incoming
                            // strokes, so a large current-page load can't OOM against resident
                            // backgrounds. Evicted backgrounds reload on demand.
                            val incoming = ((estimate ?: 0L) - alreadyResident).coerceAtLeast(0L)
                            trimBackgroundsLocked(pendingEntryBytes = incoming)
                            if (decision.overBudget) overBudgetCap = cap
                            val entry = getOrCreateEntryLocked(pageId)
                            entry.estimateBytes = estimate ?: 0L
                            // Started under the lock on purpose: creating the job and publishing it
                            // to the entry must be atomic, or a concurrent caller sees no active job
                            // and starts a second load. (A lazily-started job would report
                            // isActive=false in that window, which also unpins it from eviction.)
                            // This is a dispatch onto Dispatchers.Default — a queue append, running
                            // no user code inline — so it is safe to hold the lock across.
                            val newJob = dataLoadingScope.launch { loadPageFromDb(this, pageId) }
                            entry.loadJob = newJob
                            touchLocked(entry)
                            result = newJob
                        }
                    }
                }
            }
        }

        overBudgetCap?.let { cap ->
            log.w("Over-budget load $pageId: est=$estimate cap=$cap — loading anyway")
            appEventBus.tryEmit(
                AppEvent.LogMessage(
                    reason = "PageDataManager.admission",
                    message = "Over-budget page load: est=${(estimate ?: 0L) / 1024 / 1024}MB " +
                        "cap=${cap / 1024 / 1024}MB pageId=$pageId"
                )
            )
        }
        return result
    }

    /** Pre-load resident estimate for [pageId] from the summed compressed stroke-blob byte size. */
    private suspend fun estimatePageCostBytes(pageId: String): Long {
        val blobBytes = appRepository.strokeRepository.sumPointsLength(pageId)
        return PageMemoryModel.estimateResidentBytes(blobBytes)
    }

    /** Ensures that [pageId] is loaded as a shown page; suspends until load is finished. */
    suspend fun requestPageLoadJoin(pageId: String) {
        log.d("requestPageLoadJoin($pageId)")
        getOrStartLoadingJob(pageId, isPrefetch = false)?.join()
        // Strokes may be cached from a prior visit while the background pool evicted its bitmap;
        // reload it so a shown page never draws blank.
        ensureBackgroundLoaded(pageId)
    }

    /**
     * Cancels and removes every page still loading, except those in [keep] and [currentPage]: a
     * caller that has not caught up with a page change must not stop the load of the page in front.
     */
    fun cancelLoadsExcept(keep: Set<String>) {
        synchronized(lock) {
            val toCancel = entries
                .filter { (id, e) -> e.loadJob?.isActive == true && id !in keep && id != currentPage }
                .keys.toList()
            for (id in toCancel) {
                entries[id]?.loadJob?.cancel()
                log.d("Cancelled load for page $id")
                removePageLocked(id)
            }
        }
    }

    /** Loads [pageIds], in order, into spare budget without waiting for them. */
    suspend fun prefetch(pageIds: List<String>) {
        log.d("prefetch($pageIds)")
        try {
            for (pageId in pageIds) {
                getOrStartLoadingJob(pageId, isPrefetch = true)
                ensurePrefetchedBackground(pageId)
            }
        } catch (e: CancellationException) {
            log.i("Prefetch was cancelled: ${e.message}")
        } catch (e: Exception) {
            log.e("Error prefetching pages", e)
            appEventBus.tryEmit(
                AppEvent.ActionHint("Error encountered while caching neighbors", 5000)
            )
        }
    }

    /**
     * Warms a prefetched page's background only when the page is already resident: in that case its
     * load short-circuits and won't re-run [preLoadBackground], so if the pool evicted its
     * background we reload it here. A not-yet-loaded page loads its background as part of its fresh
     * load, so we skip it here to avoid decoding the same bitmap twice.
     */
    private suspend fun ensurePrefetchedBackground(pageId: String) {
        val alreadyLoaded = synchronized(lock) { entries[pageId]?.loaded == true }
        if (alreadyLoaded) ensureBackgroundLoaded(pageId)
    }

    /**
     * Requests that the given page is loaded, but doesn't wait.
     * If already loading, is a no-op. Loaded opportunistically (prefetch) into spare budget only.
     */
    fun requestPageLoad(pageId: String) {
        dataLoadingScope.launch {
            getOrStartLoadingJob(pageId, isPrefetch = true)
        }
    }

    private suspend fun preLoadBackground(pageId: String) {
        val pageDataFromDb = appRepository.pageRepository.getById(pageId)
        if (pageDataFromDb == null) {
            log.e("Background not found for page $pageId")
            return
        }
        val backgroundType = pageDataFromDb.getBackgroundType()
        val background = pageDataFromDb.background
        val pageNumber = when (backgroundType) {
            is BackgroundType.Pdf -> backgroundType.page
            is BackgroundType.AutoPdf -> backgroundType.getPage(
                appRepository, pageDataFromDb.notebookId, pageId
            ) ?: return

            // Native backgrounds (dotted/lined/blank) have no bitmap to cache; record that so
            // ensureBackgroundLoaded doesn't keep trying to reload a nonexistent background.
            BackgroundType.Native -> {
                synchronized(lock) { entries[pageId]?.backgroundIsNative = true }
                return
            }

            BackgroundType.Image, ImageRepeating, CoverImage -> -1
        }
        synchronized(lock) { entries[pageId]?.backgroundIsNative = false }
        val value = CachedBackground(background, pageNumber, 1f)
        log.i("Preloaded background: $value")
        // Link synchronously (not via the fire-and-forget [setBackground]) so a caller that awaited
        // this load — loadPageFromDb, then requestPageLoadJoin — actually observes the
        // background. A deferred publish would leave entry.backgroundKey null when the join runs, and
        // ensureBackgroundLoaded would launch a *second* decode of the same image/PDF page. The
        // inotify watch stays deferred (blocking file I/O, ordered against nothing).
        linkBackground(pageId, value)
        armBackgroundWatch(pageId, value)
    }

    private suspend fun loadPageFromDb(coroutineScope: CoroutineScope, pageId: String) {
        // This coroutine's own Job (identical to the entry's loadJob set in getOrStartLoadingJob).
        val myJob = coroutineScope.coroutineContext[Job]
        try {
            log.d("Loading page $pageId")
            preLoadBackground(pageId)

            // Taken before the read: a save committing after the read clears them from pending, so
            // taken later they would be in neither. Edits made from here on land in the entry.
            val (strokesBeforeRead, imagesBeforeRead) = synchronized(lock) {
                pendingLocked(pageId) { it.strokes } to pendingLocked(pageId) { it.images }
            }

            // Suspend I/O happens OUTSIDE the lock.
            val pageWithData = appRepository.pageRepository.getWithDataById(pageId)
            if (pageWithData == null) {
                log.w("Missing page Data.")
                appEventBus.tryEmit(AppEvent.ActionHint("Missing Page Data", 2000))
                return
            }

            synchronized(lock) {
                // Commit only if this load still owns the entry. A cancel landing after
                // getWithDataById returned would otherwise let this block re-create a "zombie" entry
                // the cancel path already removed. Cancel+removePageLocked are paired under [lock],
                // so a mismatched (or absent) loadJob means this result is stale — discard it.
                val entry = entries[pageId]
                if (entry == null || entry.loadJob !== myJob) {
                    log.d("Discarding stale/cancelled load result for $pageId")
                    return
                }
                if (entry.strokes != null || entry.images != null)
                    log.d("Joining edits made during page loading with the loaded page")
                entry.strokes = mergeLoadedLocked(
                    entry.strokes, pageWithData.strokes,
                    strokesBeforeRead + pendingLocked(pageId) { it.strokes }, Stroke::id
                )
                entry.images = mergeLoadedLocked(
                    entry.images, pageWithData.images,
                    imagesBeforeRead + pendingLocked(pageId) { it.images }, Image::id
                )
                recomputeEntrySizeLocked(entry)
                touchLocked(entry)
                logEstimateVsActualLocked(entry)
            }
            recomputeHeight(pageId)
        } catch (e: CancellationException) {
            log.w("Loading of page $pageId was cancelled.")
            dropUnfinishedLoad(pageId, myJob)
            throw e  // rethrow cancellation
        } catch (e: Exception) {
            // Anything else (a Room error, a corrupt stroke blob, an OOM decoding a background)
            // would otherwise leave this scope's SupervisorJob with no handler and kill the process.
            // It would also leave the entry holding a completed-but-empty load job, which only the
            // inconsistency repair in [validatePageDataLoaded] would eventually notice — so drop the
            // half-built entry here and let the next request start a clean load.
            log.e("Loading of page $pageId failed", e)
            appEventBus.tryEmit(
                AppEvent.LogMessage(
                    reason = "PageDataManager.loadPageFromDb",
                    message = "Page load failed for $pageId: ${e::class.simpleName}: ${e.message}"
                )
            )
            appEventBus.tryEmit(AppEvent.ActionHint("Could not load page", 3000))
            dropUnfinishedLoad(pageId, myJob)
        } finally {
            log.d("Finished load attempt for page $pageId")
        }
    }

    /**
     * Removes [pageId]'s entry after its load [job] failed or was cancelled, so the next request
     * starts a clean load. Leaves it alone once a newer load owns it: a cancelled read of the
     * database still runs to its end, and may finish after the page was requested again.
     */
    private fun dropUnfinishedLoad(pageId: String, job: Job?) = synchronized(lock) {
        val entry = entries[pageId]
        if (entry != null && entry.loadJob === job && !entry.loaded) removePageLocked(pageId)
    }

    /**
     * The page as loaded: its database [rows] with the edits the database doesn't have yet laid
     * over them. Those are [current], the edits made while the page was loading, and [pending], the
     * page's unsaved edits by id, which also include edits made before the page was last evicted.
     * Each id appears once, in its latest version, and removed ones are left out. Must hold [lock].
     */
    private fun <T> mergeLoadedLocked(
        current: List<T>?, rows: List<T>, pending: Map<String, T?>, id: (T) -> String,
    ): List<T> {
        val currentList = current.orEmpty()
        val currentIds = currentList.mapTo(HashSet(), id)
        val merged = ArrayList<T>(rows.size + currentList.size)
        rows.filterTo(merged) { id(it) !in pending && id(it) !in currentIds }
        merged.addAll(currentList)
        pending.values.forEach { if (it != null && id(it) !in currentIds) merged.add(it) }
        return merged
    }

    /** [pageId]'s edits not in the database yet, being saved or not, by id. Must hold [lock]. */
    private fun <T> pendingLocked(
        pageId: String, changesOf: (PageChanges) -> Map<String, T?>,
    ): Map<String, T?> {
        val pending = HashMap<String, T?>()
        saving[pageId]?.let { pending.putAll(changesOf(it)) }
        unsaved[pageId]?.let { pending.putAll(changesOf(it)) }
        return pending
    }

    /** Emits the estimate/actual ratio used to calibrate [PageMemoryModel.BLOB_EXPANSION_K]. */
    private fun logEstimateVsActualLocked(entry: PageCacheEntry) {
        val est = entry.estimateBytes
        if (est > 0) {
            val ratio = entry.sizeBytes.toDouble() / est
            log.i(
                "Cache estimate/actual ${entry.pageId}: est=${est / 1024}KB " +
                    "actual=${entry.sizeBytes / 1024}KB ratio=${"%.2f".format(ratio)} " +
                    "(ratio>1 ⇒ K too small)"
            )
        }
    }

    /** What [checkLoadStateLocked] found; [Inconsistent] is the only case needing repair. */
    private sealed interface LoadState {
        data object Loading : LoadState
        data class Settled(val loaded: Boolean) : LoadState
        data class Inconsistent(val dataLoaded: Boolean, val jobDone: Boolean, val job: Job) :
            LoadState
    }

    /**
     * Pure classification of a page's load state. No emitting, no launching, no repair — so the hot
     * query paths ([getStrokesInRectangle], [getImagesInRectangle]) can ask "is this page usable?"
     * without the answer having side effects. Must hold [lock].
     */
    private fun checkLoadStateLocked(pageId: String): LoadState {
        val entry = entries[pageId]
        val job = entry?.loadJob
        if (job?.isActive == true) return LoadState.Loading
        val jobDone = job?.isCompleted ?: false
        val dataLoaded = entry?.loaded == true
        return if (job != null && dataLoaded != jobDone) {
            LoadState.Inconsistent(dataLoaded, jobDone, job)
        } else {
            LoadState.Settled(dataLoaded)
        }
    }

    /** Whether [pageId]'s data can be read right now. Pure. Must hold [lock]. */
    private fun isUsableLocked(pageId: String): Boolean =
        (checkLoadStateLocked(pageId) as? LoadState.Settled)?.loaded == true

    /**
     * - Verifies loaded data presence and job consistency.
     * - If inconsistent (a completed/cancelled job but partial data, or vice versa), reports it,
     *   schedules a clear+reload, and returns false.
     *
     * The classification happens under [lock]; the report and the repair are issued after it is
     * released, so this never emits an event or starts a coroutine with the lock held.
     */
    fun validatePageDataLoaded(pageId: String): Boolean {
        val state = synchronized(lock) { checkLoadStateLocked(pageId) }
        return when (state) {
            is LoadState.Loading -> {
                log.d("isPageLoaded: Still loading page($pageId).")
                false
            }

            is LoadState.Settled -> state.loaded

            is LoadState.Inconsistent -> {
                appEventBus.tryEmit(
                    AppEvent.LogMessage(
                        reason = "PageDataManager.validatePageDataLoaded",
                        message = "Inconsistent state for page($pageId): dataLoaded=${state.dataLoaded}, " +
                            "jobDone=${state.jobDone}, job=${state.job}, trying to fix."
                    )
                )
                dataLoadingScope.launch {
                    synchronized(lock) {
                        entries[pageId]?.loadJob?.cancel()
                        removePageLocked(pageId)
                    }
                }
                false
            }
        }
    }

    fun collectAndPersistBitmapsBatch(
        context: Context, scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            saveTopic.buffer(10).chunked(1000).collect { pageIdBatch ->
                val uniquePageIds = pageIdBatch.distinct()
                if (uniquePageIds.isEmpty()) return@collect

                log.i("Persisting batch of bitmaps for pages: $uniquePageIds")

                for (pageId in uniquePageIds) {
                    val bitmap = synchronized(lock) { entries[pageId]?.bitmap?.get() }
                    val currentZoomLevel = viewport.zoom(pageId)
                    val currentScroll = viewport.scroll(pageId)

                    if (bitmap == null || bitmap.isRecycled) {
                        log.e("Page $pageId: Bitmap is recycled/null — cannot persist it")
                        continue
                    }

                    scope.launch(Dispatchers.IO) {
                        saveHQPagePreview(context, bitmap, pageId, currentScroll, currentZoomLevel)
                        savePageThumbnail(context, bitmap, pageId)
                    }
                }
            }
        }
    }

    /**
     * Marks [pageId] as the page in front (see [currentPage]) and returns its record, or null if
     * the page is not in the database.
     */
    suspend fun setPage(pageId: String): Page? {
        val page = appRepository.pageRepository.getById(pageId)
        currentPage = page?.id.orEmpty()
        if (page == null) {
            log.e("Page($pageId) not found;")
            appEventBus.tryEmit(AppEvent.ActionHint("Page not found", 2000))
            return null
        }
        synchronized(lock) { entries[pageId]?.let { touchLocked(it) } }
        return page
    }

    fun getCachedBitmap(pageId: String): Bitmap? = synchronized(lock) {
        entries[pageId]?.bitmap?.get()?.takeIf { !it.isRecycled && it.isMutable }
    }

    fun cacheBitmap(pageId: String, bitmap: Bitmap) = synchronized(lock) {
        getOrCreateEntryLocked(pageId).bitmap = SoftReference(bitmap)
    }

    fun getPageHeight(pageId: String): Int? = viewport.height(pageId)
    fun setPageHeight(pageId: String, height: Int) = viewport.setHeight(pageId, height)

    fun recomputeHeight(pageId: String): Int {
        // Measure under [lock], publish outside it — [PageViewportState.setHeight] commits a Compose
        // snapshot, which must never run with this hot drawing-path lock held.
        val newHeight = synchronized(lock) {
            val list = entries[pageId]?.strokes
            if (list.isNullOrEmpty()) return SCREEN_HEIGHT
            max(list.maxOf { it.bottom }.plus(50).toInt(), SCREEN_HEIGHT)
        }
        viewport.setHeight(pageId, newHeight)
        return newHeight
    }

    fun computeWidth(pageId: String): Int {
        synchronized(lock) {
            val list = entries[pageId]?.strokes
            if (list.isNullOrEmpty()) return SCREEN_WIDTH
            return max(list.maxOf { it.right }.plus(50).toInt(), SCREEN_WIDTH)
        }
    }

    /** Stored scroll for [pageId]; zero until [restorePageScroll] or [setPageScroll] sets one. */
    fun getPageScroll(pageId: String): Offset = viewport.scroll(pageId) ?: Offset.Zero

    /**
     * Sets [pageId]'s scroll for showing it: its scroll from this session if it has one, otherwise
     * its persisted [scrollY]. Always writes, so a removal scheduled by an earlier eviction of the
     * page cannot erase the scroll afterwards.
     */
    fun restorePageScroll(pageId: String, scrollY: Int) {
        viewport.setScroll(pageId, viewport.scroll(pageId) ?: Offset(0f, scrollY.toFloat()))
    }

    fun setPageScroll(pageId: String, scroll: Offset) {
        viewport.setScroll(pageId, scroll)
    }

    /** Stored zoom for [pageId]; 1f (unzoomed) for a page that has not been zoomed this session. */
    fun getPageZoom(pageId: String): Float = viewport.zoom(pageId) ?: 1f

    fun setPageZoom(pageId: String, zoom: Float) {
        viewport.setZoom(pageId, zoom)
    }

    fun getStrokes(pageId: String): List<Stroke> = synchronized(lock) {
        entries[pageId]?.strokes ?: emptyList()
    }

    fun getImages(pageId: String): List<Image> = synchronized(lock) {
        entries[pageId]?.images ?: emptyList()
    }

    /* ---------------- edits and saving ---------------- */

    // Each edit changes the cached page and records the change for [PageSaver] in one critical
    // section, so the database ends up with exactly what the cache shows.

    /** Adds [strokes] to [pageId], replacing those it has with the same ids. */
    fun addStrokes(pageId: String, strokes: List<Stroke>) = editPage(pageId) { entry, changes ->
        entry.strokes = withAdded(entry.strokes, strokes, Stroke::id, changes.strokes)
    }

    /** Replaces [pageId]'s strokes that share an id with [strokes]; others in [strokes] are ignored. */
    fun updateStrokes(pageId: String, strokes: List<Stroke>) = editPage(pageId) { entry, changes ->
        entry.strokes = entry.strokes?.let { withUpdated(it, strokes, Stroke::id, changes.strokes) }
    }

    fun removeStrokes(pageId: String, strokeIds: List<String>) = editPage(pageId) { entry, changes ->
        entry.strokes = withRemoved(entry.strokes, strokeIds, Stroke::id, changes.strokes)
    }

    /** Adds [images] to [pageId], replacing those it has with the same ids. */
    fun addImages(pageId: String, images: List<Image>) = editPage(pageId) { entry, changes ->
        entry.images = withAdded(entry.images, images, Image::id, changes.images)
    }

    /** Replaces [pageId]'s images that share an id with [images]; others in [images] are ignored. */
    fun updateImages(pageId: String, images: List<Image>) = editPage(pageId) { entry, changes ->
        entry.images = entry.images?.let { withUpdated(it, images, Image::id, changes.images) }
    }

    fun removeImages(pageId: String, imageIds: List<String>) = editPage(pageId) { entry, changes ->
        entry.images = withRemoved(entry.images, imageIds, Image::id, changes.images)
    }

    /** Records [pageId]'s stored scroll for saving; does nothing if it has none. */
    fun saveScroll(pageId: String) {
        if (pageId.isEmpty()) return
        val scroll = viewport.scroll(pageId) ?: return
        synchronized(lock) { unsavedLocked(pageId).scrollY = scroll.y.toInt() }
        unsavedSignal.trySend(Unit)
    }

    /** Applies [edit] to [pageId]'s cache entry and its unsaved changes, then wakes [PageSaver]. */
    private inline fun editPage(pageId: String, edit: (PageCacheEntry, PageChanges) -> Unit) {
        synchronized(lock) {
            val entry = getOrCreateEntryLocked(pageId)
            edit(entry, unsavedLocked(pageId))
            recomputeEntrySizeLocked(entry)
        }
        unsavedSignal.trySend(Unit)
    }

    private fun unsavedLocked(pageId: String): PageChanges =
        unsaved.getOrPut(pageId) { PageChanges() }

    private fun <T> withAdded(
        current: List<T>?, added: List<T>, id: (T) -> String, changes: MutableMap<String, T?>,
    ): List<T> {
        val ids = added.mapTo(HashSet(), id)
        added.forEach { changes[id(it)] = it }
        return current.orEmpty().filter { id(it) !in ids } + added
    }

    private fun <T> withUpdated(
        current: List<T>, updated: List<T>, id: (T) -> String, changes: MutableMap<String, T?>,
    ): List<T> {
        val byId = updated.associateBy(id)
        return current.map { old -> byId[id(old)]?.also { changes[id(it)] = it } ?: old }
    }

    private fun <T> withRemoved(
        current: List<T>?, removedIds: List<String>, id: (T) -> String,
        changes: MutableMap<String, T?>,
    ): List<T>? {
        val ids = removedIds.toHashSet()
        ids.forEach { changes[it] = null }
        return current?.filter { id(it) !in ids }
    }

    /**
     * Hands every page's unsaved changes to [PageSaver] to write. Until [finishSaving], loads still
     * lay them over the database rows, which may or may not include them yet.
     */
    internal fun takeUnsaved(): Map<String, PageChanges> = synchronized(lock) {
        for ((pageId, changes) in unsaved) {
            saving.getOrPut(pageId) { PageChanges() }.putAll(changes)
        }
        unsaved.clear()
        HashMap(saving)
    }

    /**
     * Ends the save started by [takeUnsaved]. The changes of [failedPageIds] become unsaved again,
     * under any newer ones made meanwhile, to be written by a later save.
     */
    internal fun finishSaving(failedPageIds: Set<String>) {
        synchronized(lock) {
            for (pageId in failedPageIds) {
                val failed = saving[pageId] ?: continue
                unsaved[pageId]?.let { failed.putAll(it) }
                unsaved[pageId] = failed
            }
            saving.clear()
        }
    }

    // Id -> object resolution for the undo/redo path only. Builds a transient lookup from the list
    // (the single source of truth) per call; O(N) on a cold, user-initiated action. Hot paths
    // (render, eraser, selection) work on the list / spatial queries directly, never by id.
    fun getStrokes(strokeIds: List<String>, pageId: String): List<Stroke?> = synchronized(lock) {
        val byId = entries[pageId]?.strokes?.associateBy { it.id } ?: emptyMap()
        strokeIds.map { byId[it] }
    }

    fun getImages(imageIds: List<String>, pageId: String): List<Image?> = synchronized(lock) {
        val byId = entries[pageId]?.images?.associateBy { it.id } ?: emptyMap()
        imageIds.map { byId[it] }
    }


    // Assuming Rect uses 'left', 'top', 'right', 'bottom'
    // Uses the pure [isUsableLocked] rather than [validatePageDataLoaded]: a selection query must
    // not emit an event or start a repair coroutine as a side effect of asking.
    fun getImagesInRectangle(inPageCoordinates: Rect, id: String): List<Image>? {
        synchronized(lock) {
            if (!isUsableLocked(id)) return null
            val entry = entries[id] ?: return emptyList()
            touchLocked(entry)
            val imageList = entry.images ?: return emptyList()
            return imageList.filter { image ->
                image.x < inPageCoordinates.right && (image.x + image.width) > inPageCoordinates.left && image.y < inPageCoordinates.bottom && (image.y + image.height) > inPageCoordinates.top
            }
        }
    }

    fun getStrokesInRectangle(inPageCoordinates: Rect, id: String): List<Stroke>? {
        synchronized(lock) {
            if (!isUsableLocked(id)) return null
            val entry = entries[id] ?: return emptyList()
            touchLocked(entry)
            val strokeList = entry.strokes ?: return emptyList()
            return strokeList.filter { stroke ->
                stroke.right > inPageCoordinates.left && stroke.left < inPageCoordinates.right && stroke.bottom > inPageCoordinates.top && stroke.top < inPageCoordinates.bottom
            }
        }
    }

    /** The page record for [pageId]. */
    suspend fun getPageRecord(pageId: String): Page? =
        appRepository.pageRepository.getById(pageId)

    suspend fun getPageUpdatedAt(pageId: String): Long? {
        return appRepository.pageRepository.getById(pageId)?.updatedAt?.time
    }

    fun setBackground(pageId: String, background: CachedBackground) {
        // Fire-and-forget entry point (e.g. the delayed currentBackground property setter). The load
        // path publishes synchronously via [linkBackground] instead — see [preLoadBackground].
        dataScope.launch {
            linkBackground(pageId, background)
            armBackgroundWatch(pageId, background)
        }
    }

    /**
     * Publish [background] into the shared pool and link [pageId] to it — pure in-memory work under
     * [lock], no I/O. Caller must hold no lock. Synchronous so an awaited load observes the link
     * before it returns.
     */
    private fun linkBackground(pageId: String, background: CachedBackground) {
        synchronized(lock) {
            // Merge/upgrade the shared pool: keep the higher-scale (higher-quality) bitmap.
            val existing = backgroundCache[background.id]
            if (existing == null || background.scale > existing.scale) {
                background.lastAccessSeq = ++bgAccessSeq
                backgroundCache[background.id] = background
                log.d("Cached background set: id=${background.id} scale=${background.scale}")
            } else {
                existing.lastAccessSeq = ++bgAccessSeq
                log.d("Cached background exists with equal/higher scale; reusing id=${existing.id} scale=${existing.scale}")
            }

            // Link this page to the background key.
            getOrCreateEntryLocked(pageId).backgroundKey = background.id

            // Keep the pool within its own budget line right after every addition.
            trimBackgroundsLocked()
        }
    }

    /**
     * Arm the inotify watch for [background] off the lock. Registering it stats the file and arms an
     * inotify watch (blocking I/O), so it never runs with the drawing path blocked behind it, and it
     * is deliberately not joined by the load — invalidation, not the initial render, needs it.
     */
    private fun armBackgroundWatch(pageId: String, background: CachedBackground) {
        dataScope.launch {
            val notebookId = appRepository.pageRepository.getById(pageId)?.notebookId
            val observeBg = appRepository.isObservable(notebookId)
            if (observeBg) backgroundFileWatcher.watch(pageId, background.path)
        }
    }

    /**
     * Retrieves the cached background for [pageId], or a default empty [CachedBackground] if none
     * is linked (prevents null-pointer crashes downstream).
     */
    fun getBackground(pageId: String): CachedBackground {
        return synchronized(lock) {
            val key = entries[pageId]?.backgroundKey
            val bg = if (key != null) backgroundCache[key] else null
            bg?.let { it.lastAccessSeq = ++bgAccessSeq }
            log.d("Background for page $pageId: $bg")
            bg ?: CachedBackground("", 0, 1.0f)
        }
    }

    /**
     * Drops the cached background of every page whose file changed on disk, and repaints if the
     * current page was among them.
     *
     * The watcher reports page ids (already batched and de-duplicated), so the file→pages mapping
     * and its lock stay inside [BackgroundFileWatcher] instead of being read from here without one.
     */
    private fun collectBackgroundFileChanges() {
        dataLoadingScope.launch {
            backgroundFileWatcher.invalidatedPages.collect { pageIds ->
                if (pageIds.isEmpty()) return@collect
                log.i("Background file(s) changed, invalidating pages: $pageIds")
                for (pageId in pageIds) {
                    invalidateBackground(pageId)
                    if (pageId == currentPage) {
                        CanvasEventBus.forceUpdate.emit(null)
                        appEventBus.tryEmit(
                            AppEvent.ActionHint("Background file changed", 4000)
                        )
                    }
                }
            }
        }
    }

    private fun invalidateBackground(pageId: String) {
        synchronized(lock) {
            // Remove page->bg link and drop the pooled bg if no other page references it.
            val entry = entries[pageId]
            val key = entry?.backgroundKey
            entry?.backgroundKey = null
            if (key != null) {
                val stillUsed = entries.values.any { it.backgroundKey == key }
                if (!stillUsed) {
                    backgroundCache.remove(key)
                    log.d("Invalidated background cache key=$key (no remaining pages)")
                } else {
                    log.d("Unlinked page $pageId from background key=$key (still used elsewhere)")
                }
            }
            entry?.bitmap = null // windowed bitmap for this page stays per-page
            log.d("Invalidated background cache for page: $pageId")
        }
    }

    fun onExit(targetPageId: String, windowedBitmap: Bitmap, scope: CoroutineScope) {
        log.i("Page exit, is page loaded: ${validatePageDataLoaded(targetPageId)}")
        if (validatePageDataLoaded(targetPageId)) {
            cacheBitmap(targetPageId, windowedBitmap)
            scope.launch {
                saveTopic.emit(targetPageId)
            }
            recomputeHeight(targetPageId)
            // Size accounting is kept current on every edit, so no recompute here.
        }
    }

    /** --- cleaning and memory management ---- **/

    /**
     * Removes a page and all its resources; subtracts its bytes from the running total. Refuses to
     * remove the current page. Must hold [lock].
     */
    private fun removePageLocked(pageId: String): Boolean {
        if (pageId == currentPage) {
            appEventBus.tryEmit(
                AppEvent.LogMessage(
                    reason = "PageDataManager.removePage",
                    message = "Cannot remove current page, there is a bug in code"
                )
            )
            return false
        }
        log.d("Removing page $pageId")
        val entry = entries.remove(pageId)
        if (entry != null) {
            entriesTotalBytes -= entry.sizeBytes
            // Unlink and possibly drop the pooled background.
            val key = entry.backgroundKey
            if (key != null && entries.values.none { it.backgroundKey == key }) {
                backgroundCache.remove(key)
            }
        }
        // Both of these are lock-safe by contract: they enqueue and return, running no Compose code
        // and touching no filesystem inline. That is the whole reason eviction may stay under [lock].
        viewport.scheduleRemoval(pageId)
        backgroundFileWatcher.unwatchAsync(pageId)
        assertTotalsLocked()
        return true
    }

    /** Locking wrapper for [removePageLocked]. */
    fun removePage(pageId: String): Boolean = synchronized(lock) { removePageLocked(pageId) }

    /**
     * Cancels and removes a currently loading page.
     */
    fun cancelLoadingPage(pageId: String) {
        dataLoadingScope.launch {
            log.d("Cancelling loading page: pageId=$pageId")
            synchronized(lock) {
                val entry = entries[pageId]
                if (entry?.loadJob?.isActive == true) {
                    entry.loadJob?.cancel()
                    removePageLocked(pageId)
                }
            }
        }
    }

    /**
     * Cancels and removes every page still loading, except [ignoredPageIds] and the page in front
     * (see [cancelLoadsExcept]).
     */
    fun cancelLoadingPages(ignoredPageIds: List<String> = listOf()) {
        dataLoadingScope.launch {
            log.d("Cancelling loading pages, ignoring: $ignoredPageIds")
            cancelLoadsExcept(ignoredPageIds.toSet())
        }
    }

    fun clearAllPages() {
        dataLoadingScope.launch {
            log.d("Clearing loaded pages")
            synchronized(lock) {
                for (id in entries.keys.toList()) {
                    entries[id]?.loadJob?.cancel()
                    removePageLocked(id)
                }
            }
        }
    }

    /** Resident MB currently held by the page cache (strokes/images + pooled backgrounds). */
    fun getUsedMemory(): Int = synchronized(lock) {
        (residentBytesLocked() / (1024 * 1024)).toInt()
    }

    /**
     * Evict unpinned pages, least-recently-used first, until the counted resident bytes fit the
     * heap budget.
     */
    fun trimToBudget() {
        synchronized(lock) {
            val cap = budget.capBytes()
            // Page eviction is gated on stroke/image bytes only; backgrounds are trimmed on their
            // own budget line below. On a normal notebook strokes are tiny, so this evicts nothing
            // and the current page + both neighbors stay resident (no churn).
            val victims = selectEvictions(evictCandidatesLocked(), entriesTotalBytes, 0L, cap)
            if (victims.isNotEmpty())
                log.d("trimToBudget evicting ${victims.size} page(s): $victims (cap=${cap / 1024 / 1024}MB)")
            victims.forEach { removePageLocked(it) }
            trimBackgroundsLocked()
            assertTotalsLocked()
        }
    }

    /** Drop every unpinned page (used on real device-memory pressure / backgrounding). */
    private fun dropAllUnpinned() {
        synchronized(lock) {
            val victims = entries.filter { (id, e) -> !isPinnedLocked(id, e) }.keys.toList()
            log.d("dropAllUnpinned evicting ${victims.size} page(s)")
            victims.forEach { removePageLocked(it) }
        }
    }

    fun registerComponentCallbacks(context: Context) {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                log.d("onTrimMemory: $level, usedMB: ${getUsedMemory()}")
                when (level) {
                    // Backgrounded / fully trimmed → drop everything we can.
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> dropAllUnpinned()
                    // Any other pressure level → shrink back to the heap budget.
                    else -> trimToBudget()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // No action needed for config changes
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                dropAllUnpinned()
            }
        })
    }
}
