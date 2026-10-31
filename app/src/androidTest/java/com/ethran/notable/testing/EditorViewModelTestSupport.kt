package com.ethran.notable.testing

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PagePreviewStore
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Shared helpers for the EditorViewModel page-switch regression tests
 * ([EditorUnsupportedConcurrentChangeTests] and [EditorUnsupportedConcurrentChangeComposeTests]).
 */

/** Builds a real [EditorViewModel] backed by an in-memory Room DB, mocking side-effecting deps. */
internal fun createEditorViewModelForTest(
    context: Context,
    db: AppDatabase,
    pageDataManager: PageDataManager = mockk(relaxed = true),
): EditorViewModel {
    val appRepository = createAppRepositoryForTest(context, db)
    val editorSettingCacheManager = EditorSettingCacheManager(KvRepository(db.kvDao(), context))

    val exportEngine = mockk<ExportEngine>(relaxed = true)
    val pageSaver = mockk<PageSaver>(relaxed = true)
    val syncOrchestrator = mockk<SyncOrchestrator>(relaxed = true).also {
        coEvery { it.syncFromPageId(any()) } returns Unit
    }
    val snackDispatcher = mockk<SnackDispatcher>(relaxed = true)

    val historyFactory = mockk<History.Factory>().also {
        every { it.create(any()) } returns mockk(relaxed = true)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    return EditorViewModel(
        context = context,
        appRepository = appRepository,
        editorSettingCacheManager = editorSettingCacheManager,
        exportEngine = exportEngine,
        linkCopier = mockk(relaxed = true),
        pageDataManager = pageDataManager,
        pageLeases = PageLeases(pageDataManager),
        pageSaver = pageSaver,
        pagePreviewStore = PagePreviewStore(context, pageDataManager, appRepository),
        syncOrchestrator = syncOrchestrator,
        snackDispatcher = snackDispatcher,
        historyFactory = historyFactory,
        appScope = appScope,
    )
}

internal fun dummyStroke(pageId: String): Stroke {
    val points = listOf(
        StrokePoint(x = 10f, y = 10f, pressure = 1000f),
        StrokePoint(x = 20f, y = 12f, pressure = 1000f),
        StrokePoint(x = 30f, y = 14f, pressure = 1000f),
    )
    return Stroke(
        id = UUID.randomUUID().toString(),
        size = 5f,
        pen = Pen.BALLPEN,
        top = 10f,
        bottom = 14f,
        left = 10f,
        right = 30f,
        points = points,
        pageId = pageId,
    )
}

/** Records every write to one of [SelectionState]'s snapshot delegates that happens off the main thread. */
internal class OffMainThreadWriteObserver(
    val queue: ConcurrentLinkedQueue<String>,
    private val onDispose: () -> Unit,
) {
    fun dispose() = onDispose()
}

internal fun observeOffMainThreadWrites(selectionState: SelectionState): OffMainThreadWriteObserver {
    val mainThread = Looper.getMainLooper().thread
    val watchedStates = selectionState.snapshotDelegateStatesForTest()
    val violations = ConcurrentLinkedQueue<String>()
    val handle = Snapshot.registerGlobalWriteObserver { stateObject ->
        if (stateObject in watchedStates && Thread.currentThread() != mainThread) {
            violations.add("write on ${Thread.currentThread().name}")
        }
    }
    return OffMainThreadWriteObserver(violations) { handle.dispose() }
}

/** Polls [condition] on the calling thread until true or [timeoutMs] elapses. */
internal fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

internal fun SelectionState.snapshotDelegateStatesForTest(): Set<Any> {
    return setOf(
        delegate("firstPageCut\$delegate"),
        delegate("secondPageCut\$delegate"),
        delegate("selectedStrokes\$delegate"),
        delegate("selectedImages\$delegate"),
        delegate("selectedBitmap\$delegate"),
        delegate("selectionStartOffset\$delegate"),
        delegate("selectionDisplaceOffset\$delegate"),
        delegate("selectionRect\$delegate"),
        delegate("placementMode\$delegate"),
    ).filterNotNull().toSet()
}

private fun SelectionState.delegate(fieldName: String): Any? {
    return try {
        val field = javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        field.get(this)
    } catch (e: Exception) {
        android.util.Log.e("EditorTest", "Could not find delegate field: $fieldName", e)
        null
    }
}
