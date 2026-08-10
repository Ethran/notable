package com.ethran.notable.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
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
 * [PageDataManager.lock]. One object per page replaces the old parallel maps and removes the
 * multi-map consistency hazard.
 *
 * Nullable collections: null == not loaded, empty list == loaded-but-empty.
 */
internal class PageCacheEntry(val pageId: String) {
    var strokes: MutableList<Stroke>? = null
    var images: MutableList<Image>? = null

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

    // Read from every thread, written by the suspend setPage; @Volatile so a stale read can't
    // unpin the real current page mid-evict.
    @Volatile
    var pageFromDb: Page? = null

    /**
     * Single lock for all cached page state ([entries], [entriesTotalBytes], [backgroundCache] and
     * per-entry fields). A plain monitor (not a suspending Mutex) so hot non-suspend accessors
     * (drawing, selection) can take it.
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

    private val currentPage: String
        get() = pageFromDb?.id.orEmpty()

    @Volatile
    private var currentPageNumber = -1

    fun getCurrentPageId(): String {
        return currentPage
    }

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

    /**
     * Pages currently open in a view.
     *
     * [currentPage] names only the *foreground* page, so with more than one editor view the others
     * would be evictable while still on screen — their strokes would vanish from under them under
     * memory pressure. Views pin themselves via [pinPage] / [unpinPage].
     *
     * A concurrent set so [isPinnedLocked] can read it while holding [lock] without taking a second
     * lock, and so views can pin and unpin without contending on the cache monitor.
     */
    /** One pin per open view; more than a few means a pin was not released. */
    private val MAX_EXPECTED_PINNED_PAGES = 4

    private val pinnedPages: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Pin [pageId] against eviction while a view is showing it. Idempotent. */
    fun pinPage(pageId: String) {
        if (pageId.isEmpty()) return
        pinnedPages.add(pageId)
        // A pin is released from the view's dispose path. If that is ever missed the page becomes
        // permanently unevictable, which is invisible until memory runs out — so make the leak
        // audible instead. There is one pin per open view, and views are few.
        if (pinnedPages.size > MAX_EXPECTED_PINNED_PAGES) {
            log.w("Pinned pages = ${pinnedPages.size} ($pinnedPages) — likely a leaked pin")
        }
    }

    /** Release a pin taken by [pinPage]. Idempotent. */
    fun unpinPage(pageId: String) {
        pinnedPages.remove(pageId)
    }

    /**
     * A page is pinned (never evicted) while it is open in a view, is the current page, or has an
     * active load.
     */
    private fun isPinnedLocked(pageId: String, entry: PageCacheEntry): Boolean =
        pageId == currentPage || pageId in pinnedPages || entry.loadJob?.isActive == true

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
     * a 17.6 MB screen bitmap. The previous fixed 32 MB floor was therefore smaller than one
     * background: the cap pinned to it, every non-current background was evicted on sight, and each
     * page turn paid a fresh render. Bounded by [maxBackgroundFloorFraction]. Must hold [lock].
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
     * handled internally; suspend work (cost estimate, neighbor lookup) runs outside the lock.
     *
     * [isPrefetch] pages are admitted only into spare budget and never evict. The current page
     * ([isPrefetch] = false) is never refused: it evicts unpinned pages to fit, and loads anyway
     * (emitting an over-budget telemetry event) if it still doesn't fit.
     */
    private suspend fun getOrStartLoadingJob(
        pageId: String, bookId: String?, isPrefetch: Boolean
    ): Job? {
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

        // For the current-page path, cancel loads we no longer need (keep current + neighbors).
        if (!isPrefetch && bookId != null) cancelUnnecessaryLoading(pageId, bookId)

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

    /**
     * Ensures that the page is loaded; suspends until load is finished.
     */
    suspend fun requestCurrentPageLoadJoin() {
        val bookId = pageFromDb?.notebookId
        log.d("requestCurrentPageLoadJoin($currentPage)")
        getOrStartLoadingJob(currentPage, bookId, isPrefetch = false)?.join()
        // Strokes may be cached from a prior visit while the background pool evicted its bitmap;
        // reload it so the current page never draws blank.
        ensureBackgroundLoaded(currentPage)
    }

    private suspend fun cancelUnnecessaryLoading(pageId: String, bookId: String) {
        log.d("Canceling unnecessary loading of the Page($pageId)")
        val nextPageId =
            appRepository.getNextPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
        val prevPageId =
            appRepository.getPreviousPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
        val keep = listOfNotNull(nextPageId, prevPageId, pageId).toSet()

        synchronized(lock) {
            val toCancel = entries
                .filter { (id, e) -> e.loadJob?.isActive == true && id !in keep }
                .keys.toList()
            for (id in toCancel) {
                entries[id]?.loadJob?.cancel()
                log.d("Cancelled unnecessary load for page $id")
                removePageLocked(id)
            }
        }
    }

    suspend fun cacheNeighbors() {
        val bookId = pageFromDb?.notebookId ?: return
        log.d("cacheNeighbors($currentPage)")
        try {
            val nextPageId =
                appRepository.getNextPageIdFromBookAndPage(pageId = currentPage, notebookId = bookId)
            log.d("Caching next page $nextPageId")
            nextPageId?.let {
                getOrStartLoadingJob(it, null, isPrefetch = true)
                ensureNeighborBackground(it)
            }

            val prevPageId =
                appRepository.getPreviousPageIdFromBookAndPage(
                    pageId = currentPage,
                    notebookId = bookId
                )
            log.d("Caching prev page $prevPageId")
            prevPageId?.let {
                getOrStartLoadingJob(it, null, isPrefetch = true)
                ensureNeighborBackground(it)
            }
        } catch (e: CancellationException) {
            log.i("Caching was cancelled: ${e.message}")
        } catch (e: Exception) {
            log.e("Error caching neighbor pages", e)
            appEventBus.tryEmit(
                AppEvent.ActionHint("Error encountered while caching neighbors", 5000)
            )
        }
    }

    /**
     * Warms a neighbor's background only when its page is already resident: in that case its own
     * (prefetch) load short-circuits and won't re-run [preLoadBackground], so if the pool evicted
     * its background we reload it here. A not-yet-loaded neighbor loads its background as part of
     * its fresh load, so we skip it here to avoid decoding the same bitmap twice.
     */
    private suspend fun ensureNeighborBackground(pageId: String) {
        val alreadyLoaded = synchronized(lock) { entries[pageId]?.loaded == true }
        if (alreadyLoaded) ensureBackgroundLoaded(pageId)
    }

    /**
     * Requests that the given page is loaded, but doesn't wait.
     * If already loading, is a no-op. Loaded opportunistically (prefetch) into spare budget only.
     */
    fun requestPageLoad(pageId: String) {
        dataLoadingScope.launch {
            getOrStartLoadingJob(pageId, null, isPrefetch = true)
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
        // this load — loadPageFromDb, then requestCurrentPageLoadJoin — actually observes the
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
                // Join with any strokes/images drawn during loading (append, don't replace).
                appendStrokesLocked(entry, pageWithData.strokes)
                appendImagesLocked(entry, pageWithData.images)
                recomputeEntrySizeLocked(entry)
                touchLocked(entry)
                logEstimateVsActualLocked(entry)
            }
            recomputeHeight(pageId)
        } catch (e: CancellationException) {
            log.w("Loading of page $pageId was cancelled.")
            if (!validatePageDataLoaded(pageId)) removePage(pageId)
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
            removePage(pageId)
        } finally {
            log.d("Finished load attempt for page $pageId")
        }
    }

    // Copy-on-write: build a new list and swap the reference under [lock] instead of mutating in
    // place, so a reader that took the old reference from getStrokes/getImages (lock-free, on the
    // drawing thread) iterates an immutable snapshot — no ConcurrentModification on join-during-load.
    private fun appendStrokesLocked(entry: PageCacheEntry, newStrokes: List<Stroke>) {
        val existing = entry.strokes
        entry.strokes = if (existing == null) newStrokes.toMutableList()
        else {
            log.d("Joining strokes drawn during page loading and existing strokes")
            ArrayList<Stroke>(existing.size + newStrokes.size).apply {
                addAll(existing); addAll(newStrokes)
            }
        }
    }

    private fun appendImagesLocked(entry: PageCacheEntry, newImages: List<Image>) {
        val existing = entry.images
        entry.images = if (existing == null) newImages.toMutableList()
        else {
            log.d("Joining images drawn during page loading and existing images")
            ArrayList<Image>(existing.size + newImages.size).apply {
                addAll(existing); addAll(newImages)
            }
        }
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

    /*
     * Sets current page, and starts loading it from db.
     */
    suspend fun setPage(pageId: String) {
        pageFromDb = appRepository.pageRepository.getById(pageId)
        if (pageFromDb == null) {
            log.e("Page($pageId) not found;")
            appEventBus.tryEmit(AppEvent.ActionHint("Page not found", 2000))
            currentPageNumber = -1
            return
        }
        pageFromDb?.notebookId?.let { notebookId ->
            currentPageNumber = appRepository.getPageNumber(notebookId, pageId)
        }
        synchronized(lock) { entries[pageId]?.let { touchLocked(it) } }
    }

    suspend fun refreshPageFromDb(pageId: String) {
        pageFromDb = appRepository.pageRepository.getById(pageId)
        log.i("Refresh current page, background: ${pageFromDb?.background}")
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

    /** Stored scroll for [pageId], falling back to the page's persisted scroll position. */
    fun getPageScroll(pageId: String): Offset =
        viewport.scroll(pageId) ?: Offset(0f, pageFromDb?.scroll?.toFloat() ?: 0f)

    fun setPageScroll(pageId: String, scroll: Offset) {
        viewport.setScroll(pageId, scroll)
    }

    /** Stored zoom for [pageId]; 1f (unzoomed) for a page that has not been zoomed this session. */
    fun getPageZoom(pageId: String): Float = viewport.zoom(pageId) ?: 1f

    fun setPageZoom(pageId: String, zoom: Float) {
        viewport.setZoom(pageId, zoom)
    }


    fun isTransformationAllowedForCurrentPage(): Boolean {
        return when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }
    }

    fun getCurrentPageNumber(): Int {
        if (currentPageNumber == -1)
            log.d("Current page number: $currentPageNumber")
        return currentPageNumber
    }

    fun getStrokes(pageId: String): List<Stroke> = synchronized(lock) {
        entries[pageId]?.strokes ?: emptyList()
    }

    fun setStrokes(pageId: String, strokes: List<Stroke>) = synchronized(lock) {
        val entry = getOrCreateEntryLocked(pageId)
        entry.strokes = strokes.toMutableList()
        recomputeEntrySizeLocked(entry)
    }

    fun getImages(pageId: String): List<Image> = synchronized(lock) {
        entries[pageId]?.images ?: emptyList()
    }

    fun setImages(pageId: String, images: List<Image>) = synchronized(lock) {
        val entry = getOrCreateEntryLocked(pageId)
        entry.images = images.toMutableList()
        recomputeEntrySizeLocked(entry)
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

    /**
     * Runs a DB content-write on [dataScope], catching SQL errors so a storage/device failure
     * (e.g. SQLiteDiskIOException on endTransaction) is logged with context instead of
     * escaping the coroutine and killing the process. For now this only logs and no-ops: the
     * in-memory state is untouched, so the next successful write re-persists it.
     */
    private fun launchDbWrite(op: String, block: suspend () -> Unit) {
        dataScope.launch {
            try {
                block()
            } catch (e: SQLException) {
                log.e(
                    "DB write '$op' failed on page $currentPage " +
                            "(notebook ${pageFromDb?.notebookId}): ${e.message}", e
                )
            }
        }
    }

    fun updateStrokesInDb(strokes: List<Stroke>) {
        launchDbWrite("updateStrokes(${strokes.size})") {
            appRepository.strokeRepository.update(strokes)
            bumpEditTimestamps()
        }
    }

    fun updateImagesInDb(images: List<Image>) {
        launchDbWrite("updateImages(${images.size})") {
            appRepository.imageRepository.update(images)
            bumpEditTimestamps()
        }
    }

    fun saveStrokesToDb(strokes: List<Stroke>) {
        launchDbWrite("saveStrokes(${strokes.size})") {
            try {
                appRepository.strokeRepository.create(strokes)
            } catch (_: SQLiteConstraintException) {
                // There were some rare bugs when strokes weren't unique when inserting from history
                // I'm not sure if it's still a problem, let's just show the message
                appEventBus.tryEmit(
                    AppEvent.LogMessage(
                        reason = "saveStrokesToPersistLayer",
                        message = "Attempted to create strokes that already exist"
                    )
                )
                appRepository.strokeRepository.update(strokes)
            }
            bumpEditTimestamps()
        }
    }

    fun saveImagesToDb(images: List<Image>) {
        launchDbWrite("saveImages(${images.size})") {
            appRepository.imageRepository.create(images)
            bumpEditTimestamps()
        }
    }

    fun removeStrokesFromDb(strokes: List<String>) {
        launchDbWrite("removeStrokes(${strokes.size})") {
            appRepository.strokeRepository.deleteAll(strokes)
            bumpEditTimestamps()
        }
    }

    fun removeImagesFromDb(images: List<String>) {
        launchDbWrite("removeImages(${images.size})") {
            appRepository.imageRepository.deleteAll(images)
            bumpEditTimestamps()
        }
    }

    // Bump the edit timestamps after a content write on the current page. The page timestamp is
    // the per-page dirty signal (for incremental upload); the notebook timestamp drives the
    // per-notebook sync Upload/Download decision. Both advance together on any stroke/image edit.
    private suspend fun bumpEditTimestamps() {
        val pageId = pageFromDb?.id
        if (!pageId.isNullOrEmpty()) {
            appRepository.pageRepository.touchUpdatedAt(pageId)
        }
        val notebookId = pageFromDb?.notebookId ?: return
        val notebook = appRepository.bookRepository.getById(notebookId) ?: return
        appRepository.bookRepository.update(notebook)
    }

    fun setScrollInDb() = setScrollInDb(currentPage)

    /**
     * Keyed variant. The no-arg form writes the foreground page; a view that is not the active one
     * must name its own page, since [currentPage] is app-wide.
     */
    fun setScrollInDb(pageId: String) {
        if (pageId.isEmpty()) return
        launchDbWrite("scroll") {
            appRepository.pageRepository.updateScroll(
                pageId,
                getPageScroll(pageId).y.toInt()
            )
        }
    }

    fun getBackgroundType(): BackgroundType? {
        return pageFromDb?.getBackgroundType()
    }

    /**
     * The page record for [pageId]. Keyed, unlike [pageFromDb], which is the foreground page only —
     * a view that is not the active one needs its own record to answer questions about itself.
     */
    suspend fun getPageRecord(pageId: String): Page? =
        appRepository.pageRepository.getById(pageId)

    /** Position of [pageId] within [notebookId]. Keyed counterpart to [getCurrentPageNumber]. */
    suspend fun getPageNumber(notebookId: String, pageId: String): Int =
        appRepository.getPageNumber(notebookId, pageId)

    suspend fun getPageUpdatedAt(pageId: String): Long? {
        return appRepository.pageRepository.getById(pageId)?.updatedAt?.time
    }

    fun getBackgroundName(): String {
        return pageFromDb?.background ?: "blank"
    }

    fun setCurrentBackground(background: CachedBackground) {
        setBackground(currentPage, background)
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
            // we assume that the pageId is in current notebook.
            val observeBg = appRepository.isObservable(pageFromDb?.notebookId)
            if (observeBg) backgroundFileWatcher.watch(pageId, background.path)
        }
    }

    /**
     * Retrieves the cached background for the current page, or a default empty [CachedBackground]
     * if none is linked (prevents null-pointer crashes downstream).
     */
    fun getCurrentBackground(): CachedBackground = getBackground(currentPage)

    /**
     * Keyed variant. Backgrounds are already stored per page (`entries[pageId].backgroundKey`) and
     * pooled across pages, so this needs no new state — only a page id instead of the app-wide one.
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

    suspend fun getPageNumberInCurrentNotebook(pageId: String): Int {
        val pageNumber =
            appRepository.getPageNumber(pageFromDb?.notebookId!!, pageId)
        log.d("Page number for page($pageNumber): $pageId")
        return pageNumber
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
                        CanvasEventBus.active.forceUpdate.emit(null)
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
            // Size accounting is kept current on every setStrokes/setImages, so no recompute here.
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

    /** Locking wrapper for [removePageLocked]; used by the load-cancellation path in [loadPageFromDb]. */
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
     * Cancels and removes all currently loading pages, optionally ignoring [ignoredPageIds].
     */
    fun cancelLoadingPages(ignoredPageIds: List<String> = listOf()) {
        dataLoadingScope.launch {
            log.d("Cancelling loading pages, ignoring: $ignoredPageIds")
            synchronized(lock) {
                val toCancel = entries
                    .filter { (id, e) -> e.loadJob?.isActive == true && id !in ignoredPageIds }
                    .keys.toList()
                for (id in toCancel) {
                    entries[id]?.loadJob?.cancel()
                    log.d("Cancelled job for page $id")
                    removePageLocked(id)
                }
            }
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
