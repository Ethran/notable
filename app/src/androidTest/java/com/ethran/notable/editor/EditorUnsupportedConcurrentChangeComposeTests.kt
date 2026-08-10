package com.ethran.notable.editor

import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.createEditorViewModelForTest
import com.ethran.notable.testing.dummyStroke
import com.ethran.notable.testing.observeOffMainThreadWrites
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stronger, composition-backed reproduction of the crash:
 *
 *   java.lang.IllegalStateException: Unsupported concurrent change during composition
 *
 * Unlike [EditorUnsupportedConcurrentChangeTests] (rule-free, runs everywhere), this one keeps a live
 * Compose composition reading both the toolbar state and the selection state while the page switch
 * happens — the closest reproduction of the runtime ISE.
 *
 * Because it needs a Compose test rule, it is gated by [ComposeUiSupportRule]: on devices whose
 * Compose UI-test harness wedges (Redmi Note 9 Pro / LineageOS) it is **skipped** (reported as
 * *ignored*, not failed) before the rule is applied; on capable devices (e.g. Pixel, emulators) it
 * runs normally. Force it with `-e composeUi true`.
 */
@RunWith(AndroidJUnit4::class)
class EditorUnsupportedConcurrentChangeComposeTests {

    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 60_000)
    fun selectionState_isNotWrittenOffMainThread_duringPageSwitch_compose() {
        val seeded = runBlocking {
            TestNotebookSeeder.seedNotebook(db, pageCount = 3, strokesPerPage = 10)
        }

        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        // A real composition reading both states — the concurrent reader that turns an off-main
        // SelectionState write into "Unsupported concurrent change during composition".
        composeRule.setContent {
            val state by viewModel.toolbarState.collectAsState()
            val selectionActive = viewModel.selectionState.isNonEmpty()
            Text(text = "${state.location?.pageId.orEmpty()}-$selectionActive")
        }

        runBlocking {
            viewModel.loadToolbarState(seeded.notebookId, seeded.pageIds.first())
        }

        composeRule.runOnUiThread {
            viewModel.selectionState.selectedStrokes =
                listOf(dummyStroke(pageId = seeded.pageIds.first()))
            viewModel.selectionState.placementMode = PlacementMode.Move
            Snapshot.sendApplyNotifications()
        }

        val violations = observeOffMainThreadWrites(viewModel.selectionState)
        try {
            viewModel.goToNextPage()

            composeRule.waitUntil(15_000) {
                viewModel.toolbarState.value.location?.pageId == seeded.pageIds[1]
            }
            composeRule.waitUntil(10_000) {
                viewModel.selectionState.selectedStrokes == null &&
                        viewModel.selectionState.placementMode == null
            }

            assertTrue(
                "Detected writes to SelectionState outside the Main thread: ${violations.queue.joinToString()}",
                violations.queue.isEmpty(),
            )
        } finally {
            violations.dispose()
        }
    }
}
