package com.ethran.notable.editor

import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val LOG_TAG = "EditorTest"

/**
 * Regression tests for the error:
 * java.lang.IllegalStateException: Unsupported concurrent change during composition
 *
 * Idea: Compose `mutableStateOf` (in [SelectionState]) should not be modified
 * from a thread other than Main if a composition is ongoing simultaneously.
 *
 * This test catches the *source* of the problem deterministically: it detects writes to specific
 * snapshot state outside the UI thread.
 */
@RunWith(AndroidJUnit4::class)
class EditorUnsupportedConcurrentChangeTests {

    init {
        android.util.Log.d(LOG_TAG, "Class init: thread=${Thread.currentThread().name}, pid=${Process.myPid()}")
    }

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(LoggingRule("ruleChain-outer"))
        .around(LoggingComposeRule(composeRule))

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        println("EditorTest: setUp started")
        android.util.Log.d(LOG_TAG, "setUp: Creating in-memory database")
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        android.util.Log.d(LOG_TAG, "setUp: finished")
    }

    @After
    fun tearDown() {
        println("EditorTest: tearDown started")
        android.util.Log.d(LOG_TAG, "tearDown: Closing database")
        db.close()
        android.util.Log.d(LOG_TAG, "tearDown: finished")
    }

    /**
     * This test is intended to FAIL if, during a page change, a write to the selection state
     * occurs off the Main thread (which is a common reason for crashes in Compose).
     */
    @Test
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch() {
        println("EditorTest: Test started: selectionState_isNotWrittenOffMainThread_duringPageSwitch")
        android.util.Log.d(LOG_TAG, "Test started: selectionState_isNotWrittenOffMainThread_duringPageSwitch")

        val seeded = runBlocking {
            println("EditorTest: Inside runBlocking: seeding")
            android.util.Log.d(LOG_TAG, "Inside runBlocking: seeding")
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }

        println("EditorTest: Creating ViewModel")
        android.util.Log.d(LOG_TAG, "Creating ViewModel")
        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        android.util.Log.d(LOG_TAG, "Setting compose content")
        // Start a minimal composition that reads both toolbarState and selectionState.
        // This gives us a real recomposer + snapshots.
        composeRule.setContent {
            val state by viewModel.toolbarState.collectAsState()
            val selectionActive = viewModel.selectionState.isNonEmpty()
            Text(text = "${state.pageId.orEmpty()}-$selectionActive")
        }

        android.util.Log.d(LOG_TAG, "Loading toolbar state")
        runBlocking {
            // Start on the first page in the seeded notebook.
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }

        android.util.Log.d(LOG_TAG, "Waiting for idle to setup initial selection")
        // Ensure that reset() will actually change something (otherwise the write might be optimized out by equality policy).
        composeRule.runOnIdle {
            viewModel.selectionState.selectedStrokes = listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
        }

        val mainThread = Looper.getMainLooper().thread
        val violations = ConcurrentLinkedQueue<String>()

        val watchedStates = viewModel.selectionState.snapshotDelegateStatesForTest()
        android.util.Log.d(LOG_TAG, "Watching ${watchedStates.size} state objects")

        val handle = Snapshot.registerGlobalWriteObserver { stateObject ->
            if (stateObject in watchedStates && Thread.currentThread() != mainThread) {
                violations.add("write on ${Thread.currentThread().name}")
            }
        }

        try {
            android.util.Log.d(LOG_TAG, "Calling goToNextPage")
            viewModel.goToNextPage()

            android.util.Log.d(LOG_TAG, "Waiting for page change in toolbarState")
            runBlocking {
                awaitToolbarPage(viewModel, seeded.pageIds[1])
            }

            android.util.Log.d(LOG_TAG, "Waiting for selection reset")
            runBlocking {
                awaitSelectionReset(viewModel)
            }

            android.util.Log.d(LOG_TAG, "Asserting no violations")
            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.joinToString()}",
                violations.isEmpty()
            )
        } finally {
            handle.dispose()
            android.util.Log.d(LOG_TAG, "Observer disposed")
        }
        android.util.Log.d(LOG_TAG, "Test finished: selectionState_isNotWrittenOffMainThread_duringPageSwitch")
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
        val currentPage = viewModel.toolbarState.value.pageId
        android.util.Log.d(LOG_TAG, "About to await toolbar page: current=$currentPage expected=$expectedPageId")
        withTimeout(5_000) {
            viewModel.toolbarState
                .filter { it.pageId == expectedPageId }
                .first()
        }
    }

    private suspend fun awaitSelectionReset(viewModel: EditorViewModel) {
        android.util.Log.d(
            LOG_TAG,
            "About to await selection reset: strokes=${viewModel.selectionState.selectedStrokes?.size} placement=${viewModel.selectionState.placementMode}",
        )
        withTimeout(5_000) {
            var lastLogAt = System.currentTimeMillis()
            while (viewModel.selectionState.selectedStrokes != null || viewModel.selectionState.placementMode != null) {
                val now = System.currentTimeMillis()
                if (now - lastLogAt >= 500L) {
                    android.util.Log.d(
                        LOG_TAG,
                        "Still waiting for selection reset: strokes=${viewModel.selectionState.selectedStrokes?.size} placement=${viewModel.selectionState.placementMode}",
                    )
                    lastLogAt = now
                }
                delay(16)
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            android.util.Log.d(LOG_TAG, "BeforeClass: thread=${Thread.currentThread().name}, pid=${Process.myPid()}")
        }
    }
}

private class LoggingRule(private val label: String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        android.util.Log.d(LOG_TAG, "Rule apply: $label for ${description.className}#${description.methodName}")
        return object : Statement() {
            override fun evaluate() {
                android.util.Log.d(LOG_TAG, "Rule before: $label")
                base.evaluate()
                android.util.Log.d(LOG_TAG, "Rule after: $label")
            }
        }
    }
}

private class LoggingComposeRule(
    private val delegate: ComposeContentTestRule,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        android.util.Log.d(LOG_TAG, "ComposeRule apply: start for ${description.className}#${description.methodName}")
        val applied = delegate.apply(base, description)
        android.util.Log.d(LOG_TAG, "ComposeRule apply: end")
        return object : Statement() {
            override fun evaluate() {
                android.util.Log.d(LOG_TAG, "ComposeRule evaluate: before")
                applied.evaluate()
                android.util.Log.d(LOG_TAG, "ComposeRule evaluate: after")
            }
        }
    }
}

private fun SelectionState.snapshotDelegateStatesForTest(): Set<Any> {
    // Delegates created via `var foo by mutableStateOf(...)` have a field `foo$delegate` in the bytecode.
    // We collect references to these objects and observe global Snapshot writes.
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
