package com.ethran.notable.editor

import android.content.Context
import android.os.Looper
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Regression tests for the crash:
 *
 *   java.lang.IllegalStateException: Unsupported concurrent change during composition
 *
 * Compose `mutableStateOf` backing the [SelectionState] fields must not be written from a
 * thread other than Main while a composition is running. The original offender was
 * [EditorViewModel.changePage], which reset the selection on `Dispatchers.IO`.
 *
 * Rather than waiting for the (non-deterministic) crash, these tests catch the *source*
 * deterministically: a global snapshot write observer flags any write to the watched
 * `SelectionState` delegates that happens off the Main thread during a page switch.
 *
 * Two variants are kept on purpose:
 *  - [...duringPageSwitch_noCompose]: the primary, fast, deterministic guard. No composition.
 *  - [...duringPageSwitch_compose]: exercises the same path with a live composition running,
 *    closer to the real crash scenario.
 */
@RunWith(AndroidJUnit4::class)
class EditorUnsupportedConcurrentChangeTests {

    @get:Rule
    val composeRule = createComposeRule()

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

    /**
     * Primary guard: no live composition. Drives a real page switch and asserts that the
     * selection state is never written off the Main thread.
     *
     * This neither launches an activity nor blocks on Compose idle synchronization, so it
     * cannot hang on a perpetually-busy background scope — a regression fails fast instead.
     */
    @Test(timeout = 30_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch_noCompose() {
        val seeded = runBlocking {
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        runBlocking {
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }

        // Seed a non-empty selection on the Main thread so that reset() will actually change
        // something (otherwise the write may be optimized out by the snapshot equality policy).
        runOnMain {
            viewModel.selectionState.selectedStrokes = listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
        }

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            viewModel.goToNextPage()

            runBlocking {
                awaitToolbarPage(viewModel, seeded.pageIds[1])
                awaitSelectionReset(viewModel)
            }

            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.queue.joinToString()}",
                violations.queue.isEmpty(),
            )
        } finally {
            violations.dispose()
        }
    }

    /**
     * Same assertion, but with a real composition running that reads both the toolbar state
     * and the selection state — this is the closest reproduction of the original crash, where
     * the off-main-thread write collided with an in-flight composition.
     */
    @Test(timeout = 45_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch_compose() {
        val seeded = runBlocking {
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        // A minimal composition that reads both toolbarState and selectionState. This gives us
        // a real recomposer + snapshots, so a concurrent write would surface as the crash.
        composeRule.setContent {
            val state by viewModel.toolbarState.collectAsState()
            val selectionActive = viewModel.selectionState.isNonEmpty()
            Text(text = "${state.pageId.orEmpty()}-$selectionActive")
        }

        runBlocking {
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }

        // Drive the initial selection write directly on the UI thread instead of runOnIdle:
        // the seeded ViewModel keeps a background scope busy, so the composition never reaches
        // idle and runOnIdle would block forever. runOnUiThread returns as soon as the block runs.
        composeRule.runOnUiThread {
            viewModel.selectionState.selectedStrokes = listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
            Snapshot.sendApplyNotifications()
        }

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            viewModel.goToNextPage()

            runBlocking {
                awaitToolbarPage(viewModel, seeded.pageIds[1])
                awaitSelectionReset(viewModel)
            }

            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.queue.joinToString()}",
                violations.queue.isEmpty(),
            )
        } finally {
            violations.dispose()
        }
    }

    // --------------------------------------------------------
    // Helpers
    // --------------------------------------------------------

    /** Holds a global write observer and the violations it collected; dispose after use. */
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
            if (stateObject in watchedStates && Thread.currentThread() != mainThread) {
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
        withTimeout(5_000) {
            viewModel.toolbarState
                .filter { it.pageId == expectedPageId }
                .first()
        }
    }

    private suspend fun awaitSelectionReset(viewModel: EditorViewModel) {
        withTimeout(5_000) {
            while (viewModel.selectionState.selectedStrokes != null ||
                viewModel.selectionState.placementMode != null
            ) {
                delay(16)
            }
        }
    }
}

private fun SelectionState.snapshotDelegateStatesForTest(): Set<Any> {
    // Delegates created via `var foo by mutableStateOf(...)` have a field `foo$delegate` in the
    // bytecode. We collect references to these objects and observe global Snapshot writes.
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
