package com.ethran.notable.editor

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Thread-safety tests for SelectionState.
 * This version does NOT use Compose rules to avoid Activity lifecycle overhead and potential
 * MockK/Android compatibility issues on older API levels.
 */
@RunWith(AndroidJUnit4::class)
class EditorSelectionThreadSafetyTests {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 30_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch() {
        android.util.Log.d("EditorTest", "Starting test: selectionState_isNotWrittenOffMainThread_duringPageSwitch")
        val seeded = runBlocking {
            android.util.Log.d("EditorTest", "Seeding notebook...")
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }
        android.util.Log.d("EditorTest", "Notebook seeded: ${seeded.notebookId}")

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        runBlocking {
            android.util.Log.d("EditorTest", "Loading initial toolbar state...")
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }
        android.util.Log.d("EditorTest", "Initial state loaded")

        // Seed a non-empty selection on the Main thread.
        runOnMain {
            android.util.Log.d("EditorTest", "Seeding selection on Main thread...")
            viewModel.selectionState.selectedStrokes = listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
        }
        android.util.Log.d("EditorTest", "Selection seeded")

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            android.util.Log.d("EditorTest", "Triggering goToNextPage...")
            viewModel.goToNextPage()

            runBlocking {
                android.util.Log.d("EditorTest", "Awaiting toolbar page change...")
                awaitToolbarPage(viewModel, seeded.pageIds[1])
                android.util.Log.d("EditorTest", "Toolbar page changed, awaiting selection reset...")
                awaitSelectionReset(viewModel)
                android.util.Log.d("EditorTest", "Selection reset completed")
            }

            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.queue.joinToString()}",
                violations.queue.isEmpty(),
            )
        } finally {
            android.util.Log.d("EditorTest", "Disposing violations observer")
            violations.dispose()
        }
    }

    // --------------------------------------------------------
    // Helpers (Copied from original for self-containment)
    // --------------------------------------------------------

    private class WriteObserver(
        val queue: ConcurrentLinkedQueue<String>,
        private val onDispose: () -> Unit,
    ) {
        fun dispose() = onDispose()
    }

    private fun observeOffMainThreadWrites(selectionState: SelectionState): WriteObserver {
        val mainThread = Looper.getMainLooper().thread
        val watchedStates = selectionState.snapshotDelegateStatesForTest()
        val violations = ConcurrentLinkedQueue<String>()
        val handle = Snapshot.registerGlobalWriteObserver { stateObject ->
            if ((stateObject in watchedStates) && (Thread.currentThread() != mainThread)) {
                violations.add("write on ${Thread.currentThread().name}")
            }
        }
        return WriteObserver(violations) { handle.dispose() }
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun createEditorViewModelForTest(context: Context, db: AppDatabase): EditorViewModel {
        val bookRepository = BookRepository(db.notebookDao(), db.pageDao())
        val pageRepository = PageRepository(db.pageDao())
        val strokeRepository = StrokeRepository(db.strokeDao())
        val imageRepository = ImageRepository(db.ImageDao())
        val folderRepository = FolderRepository(db.folderDao())

        val kvRepository = KvRepository(db.kvDao(), context)
        val kvProxy = KvProxy(kvRepository, CryptoHelper())

        val appRepository = AppRepository(
            bookRepository = bookRepository,
            pageRepository = pageRepository,
            strokeRepository = strokeRepository,
            imageRepository = imageRepository,
            folderRepository = folderRepository,
            kvProxy = kvProxy,
        )

        val editorSettingCacheManager = EditorSettingCacheManager(kvRepository)

        val exportEngine = mockk<ExportEngine>(relaxed = true)
        val pageDataManager = mockk<PageDataManager>(relaxed = true)
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
            pageDataManager = pageDataManager,
            syncOrchestrator = syncOrchestrator,
            snackDispatcher = snackDispatcher,
            historyFactory = historyFactory,
            appScope = appScope,
        )
    }

    private fun dummyStroke(pageId: String): Stroke {
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

    private suspend fun awaitToolbarPage(viewModel: EditorViewModel, expectedPageId: String) {
        withTimeout(5.seconds) {
            viewModel.toolbarState
                .first { it.pageId == expectedPageId }
        }
    }

    private suspend fun awaitSelectionReset(viewModel: EditorViewModel) {
        withTimeout(5.seconds) {
            while (viewModel.selectionState.selectedStrokes != null ||
                viewModel.selectionState.placementMode != null
            ) {
                delay(16.milliseconds)
            }
        }
    }
}

private fun SelectionState.snapshotDelegateStatesForTest(): Set<Any> {
    return setOf(
        delegate($$"firstPageCut$delegate"),
        delegate($$"secondPageCut$delegate"),
        delegate($$"selectedStrokes$delegate"),
        delegate($$"selectedImages$delegate"),
        delegate($$"selectedBitmap$delegate"),
        delegate($$"selectionStartOffset$delegate"),
        delegate($$"selectionDisplaceOffset$delegate"),
        delegate($$"selectionRect$delegate"),
        delegate($$"placementMode$delegate"),
    ).asSequence().filterNotNull().toSet()
}

private fun SelectionState.delegate(fieldName: String): Any? {
    return try {
        val field = javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        field[this]
    } catch (e: Exception) {
        android.util.Log.e("EditorTest", "Could not find delegate field: $fieldName", e)
        null
    }
}
