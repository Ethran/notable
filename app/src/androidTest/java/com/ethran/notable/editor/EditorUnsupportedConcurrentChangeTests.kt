package com.ethran.notable.editor

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.createEditorViewModelForTest
import com.ethran.notable.testing.dummyStroke
import com.ethran.notable.testing.observeOffMainThreadWrites
import com.ethran.notable.testing.waitForCondition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the crash:
 *
 *   java.lang.IllegalStateException: Unsupported concurrent change during composition
 *
 * Device-independent variant: it deliberately does NOT use a Compose test rule, so it runs on every
 * device — including ones where the Compose UI-test harness wedges (Redmi Note 9 Pro / LineageOS; see
 * [com.ethran.notable.testing.ComposeUiTestSupport]). The real assertion — that no [com.ethran.notable.editor.state.SelectionState]
 * snapshot write happens off the main thread during a page switch — is caught by
 * `Snapshot.registerGlobalWriteObserver`, which needs no running composition.
 *
 * The stronger, composition-backed reproduction lives in [EditorUnsupportedConcurrentChangeComposeTests],
 * which is skipped on devices whose Compose harness wedges.
 */
@RunWith(AndroidJUnit4::class)
class EditorUnsupportedConcurrentChangeTests {

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
     * Drives a real page switch through the ViewModel and asserts that no [com.ethran.notable.editor.state.SelectionState] snapshot
     * write happens off the main thread — the root of the "Unsupported concurrent change during
     * composition" crash. Uses `Snapshot.registerGlobalWriteObserver` (no composition needed).
     */
    @Test(timeout = 60_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.util.Log.i(
            "EditorTest",
            "Starting test: selectionState_isNotWrittenOffMainThread_duringPageSwitch"
        )
        val seeded = runBlocking {
            android.util.Log.i("EditorTest", "Seeding notebook...")
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }
        android.util.Log.i("EditorTest", "Notebook seeded: ${seeded.notebookId}")

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        // Load initial toolbar state (suspend Room I/O; runs on this test thread, off the main thread).
        runBlocking {
            android.util.Log.i("EditorTest", "Loading initial toolbar state...")
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }
        android.util.Log.i("EditorTest", "Initial state loaded")

        // Seed a selection ON the main thread (as the real app always does).
        android.util.Log.i("EditorTest", "Seeding selection on main thread...")
        instrumentation.runOnMainSync {
            viewModel.selectionState.selectedStrokes =
                listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
            Snapshot.sendApplyNotifications()
        }
        android.util.Log.i("EditorTest", "Selection seeded")

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            android.util.Log.i("EditorTest", "Triggering goToNextPage...")
            viewModel.goToNextPage()

            // Poll from this (instrumentation) thread; the main looper stays free to run the
            // ViewModel's `withContext(Dispatchers.Main.immediate) { selectionState.reset() }`.
            android.util.Log.i("EditorTest", "Awaiting toolbar page change...")
            val pageChanged = waitForCondition(15_000) {
                viewModel.toolbarState.value.location?.pageId == seeded.pageIds[1]
            }
            assertTrue("Toolbar page did not change to the next page in time", pageChanged)

            android.util.Log.i("EditorTest", "Toolbar page changed, awaiting selection reset...")
            val selectionReset = waitForCondition(10_000) {
                viewModel.selectionState.selectedStrokes == null &&
                        viewModel.selectionState.placementMode == null
            }
            assertTrue("SelectionState was not reset in time", selectionReset)
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
}
