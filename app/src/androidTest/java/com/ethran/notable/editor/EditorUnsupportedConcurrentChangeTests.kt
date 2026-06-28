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
 */
@RunWith(AndroidJUnit4::class)
class EditorUnsupportedConcurrentChangeTests {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        android.util.Log.i("EditorTest", "setUp: creating in-memory DB")
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        android.util.Log.i("EditorTest", "setUp: DB created")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Assertion with a real composition running that reads both the toolbar state
     * and the selection state — this is the closest reproduction of the original crash.
     */
    @Test(timeout = 60_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch_compose() {
        android.util.Log.i("EditorTest", "Starting test: selectionState_isNotWrittenOffMainThread_duringPageSwitch_compose")
        val seeded = runBlocking {
            android.util.Log.i("EditorTest", "Seeding notebook...")
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }
        android.util.Log.i("EditorTest", "Notebook seeded: ${seeded.notebookId}")

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        android.util.Log.i("EditorTest", "Setting compose content...")
        composeRule.setContent {
            val state by viewModel.toolbarState.collectAsState()
            val selectionActive = viewModel.selectionState.isNonEmpty()
            Text(text = "${state.pageId.orEmpty()}-$selectionActive")
        }

        composeRule.runOnUiThread {
            runBlocking {
                android.util.Log.i("EditorTest", "Loading initial toolbar state...")
                viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
            }
        }
        android.util.Log.i("EditorTest", "Initial state loaded")

        android.util.Log.i("EditorTest", "Seeding selection on UI thread...")
        composeRule.runOnUiThread {
            try {
                viewModel.selectionState.selectedStrokes = listOf(dummyStroke(pageId = seeded.pageIds.first()))
                viewModel.selectionState.placementMode = PlacementMode.Move
                Snapshot.sendApplyNotifications()
            } catch (e: Throwable) {
                android.util.Log.e("EditorTest", "Error during selection seeding", e)
            }
        }
        android.util.Log.i("EditorTest", "Selection seeded")

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            android.util.Log.i("EditorTest", "Triggering goToNextPage...")
            viewModel.goToNextPage()

            android.util.Log.i("EditorTest", "Awaiting toolbar page change...")
            composeRule.waitUntil(15_000) {
                viewModel.toolbarState.value.pageId == seeded.pageIds[1]
            }

            android.util.Log.i("EditorTest", "Toolbar page changed, awaiting selection reset...")
            composeRule.waitUntil(10_000) {
                viewModel.selectionState.selectedStrokes == null &&
                        viewModel.selectionState.placementMode == null
            }
            android.util.Log.i("EditorTest", "Selection reset completed")

            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.queue.joinToString()}",
                violations.queue.isEmpty(),
            )
        } finally {
            android.util.Log.i("EditorTest", "Disposing violations observer")
            violations.dispose()
        }
    }

    // --------------------------------------------------------
    // Helpers
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
            if (stateObject in watchedStates && Thread.currentThread() != mainThread) {
                violations.add("write on ${Thread.currentThread().name}")
            }
        }
        return WriteObserver(violations) { handle.dispose() }
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
}

private fun SelectionState.snapshotDelegateStatesForTest(): Set<Any> {
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
