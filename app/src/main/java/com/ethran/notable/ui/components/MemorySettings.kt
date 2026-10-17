package com.ethran.notable.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ethran.notable.data.BackgroundMemoryRow
import com.ethran.notable.data.MemorySnapshot
import com.ethran.notable.data.PageDataManagerEntryPoint
import com.ethran.notable.data.PageMemoryRow
import dagger.hilt.EntryPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val REFRESH_MS = 1000L
private const val COPIED_HINT_MS = 2000L

// Ink-friendly greys: these have to stay distinguishable on a 16-level e-paper panel, so the
// segments of a single bar are spaced far apart rather than shaded finely.
private val COLOR_COUNTED = Color(0xFF1B1B1B)
private val COLOR_SECOND = Color(0xFF7A7A7A)
private val COLOR_UNTRACKED = Color(0xFFC4C4C4)
private val COLOR_OVER_BUDGET = Color(0xFF000000)

/**
 * Debug-tab view of where the app's memory has gone.
 *
 * Mirrors the separation `PageDataManager` itself maintains, and does not merge the pools: page
 * entries are Java heap on their own eviction budget, the background pool is native memory on a
 * second, independently-evicted budget, and the windowed page bitmaps are native memory on no budget
 * at all. Rolling those into one "used" figure is what hides which pool is actually growing.
 *
 * Samples `PageDataManager.memorySnapshot` once a second, off the main thread — the snapshot is
 * O(#resident strokes) and takes the same lock as the drawing path. Sampling runs only while the
 * section is expanded *and* the activity is resumed, so a collapsed section or a backgrounded app
 * costs nothing.
 */
@Composable
fun MemorySettings() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val pageDataManager = remember(context) {
        EntryPoints.get(context.applicationContext, PageDataManagerEntryPoint::class.java)
            .pageDataManager()
    }

    var expanded by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    // Keyed by tick rather than a plain flag so tapping Copy again restarts the hint's full timeout
    // instead of inheriting the first tap's remaining time.
    var copyTick by remember { mutableIntStateOf(0) }
    var showCopied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    suspend fun sample() {
        snapshot = withContext(Dispatchers.Default) { pageDataManager.memorySnapshot() }
    }

    LaunchedEffect(copyTick) {
        if (copyTick == 0) return@LaunchedEffect
        showCopied = true
        delay(COPIED_HINT_MS)
        showCopied = false
    }

    LaunchedEffect(expanded, lifecycleOwner) {
        if (!expanded) return@LaunchedEffect
        // Suspends while the app is backgrounded instead of polling a cache nobody is looking at.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                sample()
                delay(REFRESH_MS)
            }
        }
    }

    val clipboard = LocalClipboard.current
    MemorySettingsContent(
        expanded = expanded,
        snapshot = snapshot,
        showCopied = showCopied,
        onToggleExpanded = { expanded = !expanded },
        onRefresh = { scope.launch { sample() } },
        onCopy = { snap ->
            val report = reportText(snap)
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("Notable memory report", report))
                )
                copyTick++
            }
        },
    )
}

/**
 * The view itself, with no dependency on Hilt, the sampler, or a lifecycle — everything it draws
 * arrives as parameters, so each state it can be in is reachable from a `@Preview`.
 */
@Composable
private fun MemorySettingsContent(
    expanded: Boolean,
    snapshot: MemorySnapshot?,
    showCopied: Boolean,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: (MemorySnapshot) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (expanded) "Memory usage ▾" else "Memory usage ▸",
            style = MaterialTheme.typography.subtitle1,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
        )
        if (!expanded) {
            Text(
                text = "Tap to show the page-entry and background-pool budgets.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
            return@Column
        }

        val snap = snapshot ?: run {
            Text("Sampling…", style = MaterialTheme.typography.caption)
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        JavaHeapSection(snap)
        Spacer(Modifier.height(10.dp))
        NativeHeapSection(snap)
        Spacer(Modifier.height(10.dp))

        BreakdownTable(snap)

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onRefresh) { Text("Refresh") }
            Button(onClick = { onCopy(snap) }) { Text("Copy") }
            if (showCopied) Text("Copied", style = MaterialTheme.typography.caption)
        }
    }
}

/**
 * Java heap: the page-entry cache is the only pool that lives here, so it is the only counted
 * segment. The rest is Compose/UI and a load's transient stroke copy.
 */
@Composable
private fun JavaHeapSection(snap: MemorySnapshot) {
    SectionHeader("Java heap — used ${mb(snap.usedHeapBytes)} of ${mb(snap.maxHeapBytes)} max")
    StackedBar(
        total = snap.maxHeapBytes,
        segments = listOf(
            snap.entryBytes to COLOR_COUNTED,
            snap.untrackedHeapBytes to COLOR_UNTRACKED,
        )
    )
    Caption("page entries ${mb(snap.entryBytes)} · other ${mb(snap.untrackedHeapBytes)}")
    if (snap.heapOverAccounted) {
        // PageMemoryModel over-estimates on purpose (evict rather than OOM), so this is a magnitude
        // readout, not a defect report: past 100% the margin has stopped being a safety margin.
        Caption(
            "Modelled entry bytes are ${pctOf(snap.entryBytes, snap.usedHeapBytes)} of measured " +
                "heap use. The model over-estimates by design, but above 100% it cannot be " +
                "calibrated from here and \"other\" is clamped to zero."
        )
    }

    BudgetLine("Page entries (strokes + images)", snap.entryBytes, snap.entryCapBytes)
    Caption(
        "Own eviction line: LRU pages are dropped to fit this cap. Backgrounds are not counted " +
            "against it. Cap = ${pct(snap.heapBudgetFraction)} of max heap."
    )
}

/**
 * Native heap: both bitmap pools live here (minSdk 29 ⇒ bitmap pixels are native), and they are
 * managed independently of each other and of the page-entry cache.
 */
@Composable
private fun NativeHeapSection(snap: MemorySnapshot) {
    SectionHeader("Native heap — ${mb(snap.nativeHeapBytes)} allocated")
    StackedBar(
        total = snap.nativeHeapBytes,
        segments = listOf(
            snap.backgroundBytes to COLOR_COUNTED,
            snap.bitmapBytes to COLOR_SECOND,
            snap.untrackedNativeBytes to COLOR_UNTRACKED,
        )
    )
    Caption(
        "background pool ${mb(snap.backgroundBytes)} · windowed bitmaps ${mb(snap.bitmapBytes)}" +
            " · other ${mb(snap.untrackedNativeBytes)}"
    )
    if (snap.nativeOverAccounted) {
        Caption(
            "⚠ Counted bitmaps (${mb(snap.countedNativeBytes)}) exceed the measured native heap — " +
                "some are likely hardware/graphics-backed, which allocationByteCount reports but " +
                "getNativeHeapAllocatedSize does not. The split above is unreliable."
        )
    }

    BudgetLine("Background pool (shared, deduped)", snap.backgroundBytes, snap.backgroundCapBytes)
    Caption(
        "Own eviction line, independent of page eviction. Cap shrinks as page entries grow " +
            "(heap ceiling − 2× page entries), so it moves even when no background changes."
    )

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Windowed page bitmaps",
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(1f)
        )
        Text(mb(snap.bitmapBytes), style = MaterialTheme.typography.caption)
    }
    Caption("No budget line: SoftReferences, reclaimed by ART under pressure rather than evicted.")
}

/**
 * The per-page and per-background tables. Scrolls in both directions: the rows are fixed-width
 * monospace, so wrapping them would destroy the column alignment they depend on.
 */
@Composable
private fun BreakdownTable(snap: MemorySnapshot) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = breakdownText(snap),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = MaterialTheme.colors.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.body2)
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    )
}

/** One budget line: a fill bar plus `used / cap` and the percentage. */
@Composable
private fun BudgetLine(label: String, used: Long, cap: Long) {
    val percent = if (cap > 0) (used * 100 / cap) else 0L
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
            Text("${mb(used)} / ${mb(cap)} ($percent%)", style = MaterialTheme.typography.caption)
        }
        StackedBar(
            total = cap.coerceAtLeast(used),
            // Over-budget reads as a solid black bar rather than silently clipping at 100%.
            segments = listOf(used to if (used > cap) COLOR_OVER_BUDGET else COLOR_COUNTED)
        )
    }
}

/**
 * A proportional horizontal bar. Segments are drawn in order and clipped to [total]; the remainder
 * stays as the (empty) track.
 */
@Composable
private fun StackedBar(total: Long, segments: List<Pair<Long, Color>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .border(1.dp, Color.Black)
            .background(Color.White)
    ) {
        if (total <= 0) return@Row
        var remaining = total
        for ((bytes, color) in segments) {
            val shown = bytes.coerceIn(0L, remaining)
            if (shown == 0L) continue
            remaining -= shown
            // Compose rejects a zero weight, so floor the ratio of a sub-pixel segment.
            Box(
                modifier = Modifier
                    .weight(weightOf(shown, total))
                    .fillMaxHeight()
                    .background(color)
            )
        }
        if (remaining > 0) Spacer(Modifier.weight(weightOf(remaining, total)))
    }
}

/* ---------------- text breakdown ---------------- */

// Header labels and their column widths in one place: rows are rendered from the same widths, so a
// header and its data cannot drift apart.
private val PAGE_COLUMNS = listOf(
    "id" to 9, "flags" to 6, "lru" to 5, "bytes" to 11,
    "estimate" to 11, "strokes" to 9, "points" to 12, "img" to 6,
)

private val BACKGROUND_COLUMNS = listOf(
    "id" to 18, "lru" to 5, "bytes" to 11, "pdfpage" to 9, "scale" to 7, "state" to 9,
)

/**
 * Per-page and per-background detail, monospaced so the columns line up. The two tables stay
 * separate because the pools are: a page row's `bmp` column is native memory on no budget, and a
 * background row may be shared by several pages, so its bytes belong to neither page alone.
 */
private fun breakdownText(snap: MemorySnapshot): String = buildString {
    // Rank 1 = least recently used = first to be evicted. The table is sorted by size, so without
    // this the eviction order — the thing you actually want when diagnosing churn — is invisible.
    val pageLru = lruRanks(snap.pages.map { it.pageId to it.lastAccessSeq })

    appendLine("PAGE ENTRIES — Java heap, entry budget (${snap.pages.size} cached, largest first)")
    appendLine(headerRow(PAGE_COLUMNS))
    if (snap.pages.isEmpty()) appendLine("  <none>")
    for (p in snap.pages) {
        // S=shown (leased), P=pinned, L=loading, ?=metadata only (not fully loaded).
        val flags = buildString {
            append(if (p.leased) "S" else "-")
            append(if (p.pinned) "P" else "-")
            append(if (p.loading) "L" else "-")
            append(if (p.loaded) "-" else "?")
        }
        val cells = listOf(
            p.pageId.take(8),
            flags,
            // A pinned page is never evicted, so an LRU rank for it would be misleading.
            if (p.pinned) "pin" else "${pageLru[p.pageId]}",
            mb(p.sizeBytes),
            mb(p.estimateBytes),
            "${p.strokeCount}",
            "${p.pointCount}",
            "${p.imageCount}",
        )
        appendLine(
            dataRow(cells, PAGE_COLUMNS) +
                // Native and unbudgeted — tagged inline so it is not read as part of the entry bytes.
                (if (p.bitmapBytes > 0) " bmp(native) ${mb(p.bitmapBytes)}" else "") +
                (p.backgroundKey?.let { " bg $it" } ?: "")
        )
    }

    appendLine()
    val bgLru = lruRanks(snap.backgrounds.map { it.id to it.lastAccessSeq })
    appendLine("BACKGROUND POOL — native, own budget (${snap.backgrounds.size} bitmaps, deduped)")
    appendLine(headerRow(BACKGROUND_COLUMNS))
    if (snap.backgrounds.isEmpty()) appendLine("  <none>")
    for (b in snap.backgrounds) {
        val cells = listOf(
            b.id,
            "${bgLru[b.id]}",
            mb(b.bytes),
            // -1 marks a whole-image background, which has no PDF page to report.
            if (b.pageNumber >= 0) "${b.pageNumber}" else "—",
            String.format(Locale.US, "%.2f", b.scale),
            if (b.resident) "resident" else "freed",
        )
        appendLine(dataRow(cells, BACKGROUND_COLUMNS) + " ${b.name}")
    }
}

/** Maps each id to its LRU rank, 1 = least recently used = evicted first. */
private fun lruRanks(idsWithSeq: List<Pair<String, Long>>): Map<String, Int> =
    idsWithSeq.sortedBy { it.second }
        .mapIndexed { index, (id, _) -> id to index + 1 }
        .toMap()

private fun headerRow(columns: List<Pair<String, Int>>): String =
    "  " + columns.joinToString("") { (label, width) -> pad(label, width) }

private fun dataRow(cells: List<String>, columns: List<Pair<String, Int>>): String =
    "  " + cells.mapIndexed { i, cell -> pad(cell, columns[i].second) }.joinToString("")

/** The whole view as plain text, for pasting into a bug report. */
private fun reportText(snap: MemorySnapshot): String = buildString {
    appendLine("JAVA HEAP  used ${mb(snap.usedHeapBytes)} / total ${mb(snap.totalHeapBytes)} / max ${mb(snap.maxHeapBytes)}")
    appendLine("  page entries   ${mb(snap.entryBytes)} / ${mb(snap.entryCapBytes)} cap  (own eviction line)")
    appendLine("  other          ${mb(snap.untrackedHeapBytes)}  (UI, transient load copies)")
    appendLine("  budget fraction ${pct(snap.heapBudgetFraction)} of max heap")
    if (snap.heapOverAccounted) appendLine("  ** entry bytes exceed used heap: PageMemoryModel over-estimates **")
    appendLine()
    appendLine("NATIVE HEAP  ${mb(snap.nativeHeapBytes)} allocated")
    appendLine("  background pool  ${mb(snap.backgroundBytes)} / ${mb(snap.backgroundCapBytes)} cap  (own eviction line)")
    appendLine("  windowed bitmaps ${mb(snap.bitmapBytes)}  (no budget, SoftReference)")
    appendLine("  other            ${mb(snap.untrackedNativeBytes)}")
    if (snap.nativeOverAccounted) appendLine("  ** counted bitmaps exceed measured native heap: split unreliable **")
    appendLine()
    appendLine("Shown pages: ${snap.leasedPageIds.ifEmpty { listOf("<none>") }.joinToString()}")
    appendLine()
    append(breakdownText(snap))
}

private fun weightOf(part: Long, total: Long): Float =
    (part.toFloat() / total.toFloat()).coerceAtLeast(0.0001f)

private fun pct(fraction: Double): String = "${(fraction * 100).toInt()}%"

private fun pctOf(part: Long, whole: Long): String =
    if (whole <= 0) "n/a" else "${part * 100 / whole}%"

// Locale.US throughout: this text is copied into bug reports, and a locale-dependent decimal
// separator would also break the monospace column alignment above.
private fun mb(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun pad(s: String, width: Int): String = s.padEnd(width)

/* ---------------- previews ---------------- */

private const val MB = 1024L * 1024

// Onyx-sized figures (a 1404×1872 ARGB_8888 screen bitmap is ~10.5 MB) so the previews show the
// proportions this actually renders at on the device, not toy numbers.
private const val SCREEN_BITMAP_BYTES = 1404L * 1872 * 4

private fun previewPage(
    id: String,
    sizeMb: Long,
    estimateMb: Long,
    strokes: Int,
    points: Long,
    images: Int = 0,
    withBitmap: Boolean = false,
    background: String? = null,
    loaded: Boolean = true,
    loading: Boolean = false,
    pinned: Boolean = false,
    leased: Boolean = false,
    seq: Long = 0L,
) = PageMemoryRow(
    pageId = id,
    strokeCount = strokes,
    pointCount = points,
    imageCount = images,
    sizeBytes = sizeMb * MB,
    estimateBytes = estimateMb * MB,
    bitmapBytes = if (withBitmap) SCREEN_BITMAP_BYTES else 0L,
    backgroundKey = background,
    loaded = loaded,
    loading = loading,
    pinned = pinned,
    leased = leased,
    lastAccessSeq = seq,
)

private val PREVIEW_PAGES = listOf(
    previewPage(
        id = "a3f19c2e77", sizeMb = 18, estimateMb = 20, strokes = 12_400, points = 1_912_000,
        images = 2, withBitmap = true, background = "a1b2c3d4e5f60718",
        pinned = true, leased = true, seq = 1042,
    ),
    previewPage(
        id = "b7c04d1a90", sizeMb = 14, estimateMb = 12, strokes = 9_800, points = 1_403_500,
        withBitmap = true, background = "0f1e2d3c4b5a6978", seq = 1039,
    ),
    previewPage(
        id = "c1d8e6b452", sizeMb = 10, estimateMb = 11, strokes = 6_100, points = 902_300,
        images = 1, loaded = false, loading = true, pinned = true, seq = 1041,
    ),
)

private val PREVIEW_BACKGROUNDS = listOf(
    BackgroundMemoryRow(
        id = "a1b2c3d4e5f60718", name = "field-manual.pdf", pageNumber = 12, scale = 1f,
        bytes = 62 * MB, resident = true, lastAccessSeq = 311,
    ),
    BackgroundMemoryRow(
        id = "0f1e2d3c4b5a6978", name = "grid-cover.png", pageNumber = -1, scale = 2f,
        bytes = 34 * MB, resident = false, lastAccessSeq = 308,
    ),
)

private fun previewSnapshot(
    usedHeapMb: Long = 290,
    entryMb: Long = 42,
    entryCapMb: Long = 358,
    nativeMb: Long = 210,
    backgroundMb: Long = 96,
    backgroundCapMb: Long = 274,
    pages: List<PageMemoryRow> = PREVIEW_PAGES,
    backgrounds: List<BackgroundMemoryRow> = PREVIEW_BACKGROUNDS,
) = MemorySnapshot(
    maxHeapBytes = 512 * MB,
    // usedHeap is derived as total − free, so pick a total and back the free value out of it.
    totalHeapBytes = 380 * MB,
    freeHeapBytes = (380 - usedHeapMb) * MB,
    nativeHeapBytes = nativeMb * MB,
    heapBudgetFraction = 0.7,
    entryCapBytes = entryCapMb * MB,
    entryBytes = entryMb * MB,
    backgroundCapBytes = backgroundCapMb * MB,
    backgroundBytes = backgroundMb * MB,
    leasedPageIds = pages.filter { it.leased }.map { it.pageId },
    pages = pages,
    backgrounds = backgrounds,
)

@Composable
private fun PreviewFrame(
    snapshot: MemorySnapshot?,
    expanded: Boolean = true,
    showCopied: Boolean = false,
) {
    Box(Modifier.background(Color.White).padding(horizontal = 16.dp)) {
        MemorySettingsContent(
            expanded = expanded,
            snapshot = snapshot,
            showCopied = showCopied,
            onToggleExpanded = {},
            onRefresh = {},
            onCopy = {},
        )
    }
}

/** The ordinary case: both budgets comfortably inside their caps. */
@Preview(showBackground = true, widthDp = 600, heightDp = 720)
@Composable
private fun MemorySettingsPreview() = PreviewFrame(previewSnapshot())

/** Collapsed — what the Debug tab shows until you tap it. */
@Preview(showBackground = true, widthDp = 600)
@Composable
private fun MemorySettingsCollapsedPreview() = PreviewFrame(previewSnapshot(), expanded = false)

/** Expanded before the first sample lands. */
@Preview(showBackground = true, widthDp = 600)
@Composable
private fun MemorySettingsSamplingPreview() = PreviewFrame(snapshot = null)

/** Nothing cached: exercises the zero-total bars and the `<none>` rows. */
@Preview(showBackground = true, widthDp = 600, heightDp = 560)
@Composable
private fun MemorySettingsEmptyPreview() = PreviewFrame(
    previewSnapshot(entryMb = 0, backgroundMb = 0, pages = emptyList(), backgrounds = emptyList())
)

/**
 * Both pools over their caps *and* both accounting checks tripped — the state the view exists to
 * make obvious. Entry bytes exceed the used heap and the counted bitmaps exceed the native heap, so
 * both ⚠ captions render and both budget bars go solid black.
 */
@Preview(showBackground = true, widthDp = 600, heightDp = 800)
@Composable
private fun MemorySettingsOverBudgetPreview() = PreviewFrame(
    previewSnapshot(
        usedHeapMb = 120, entryMb = 180, entryCapMb = 100,
        nativeMb = 40, backgroundMb = 96, backgroundCapMb = 64,
    )
)

/** Copy feedback visible. */
@Preview(showBackground = true, widthDp = 600, heightDp = 720)
@Composable
private fun MemorySettingsCopiedPreview() = PreviewFrame(previewSnapshot(), showCopied = true)
