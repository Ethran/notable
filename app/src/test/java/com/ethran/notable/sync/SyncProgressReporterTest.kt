package com.ethran.notable.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressReporterTest {

    private fun newReporter(): SyncProgressReporter =
        SyncProgressReporterImpl(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test
    fun initial_state_is_idle() {
        val reporter = newReporter()
        assertEquals(SyncState.Idle, reporter.state.value)
    }

    @Test
    fun beginStep_emits_syncing_with_step_details_and_null_item() {
        val reporter = newReporter()

        reporter.beginStep(SyncStep.SYNCING_FOLDERS, 0.1f, "Syncing folders...")

        val s = reporter.state.value
        assertTrue("Expected Syncing, got $s", s is SyncState.Syncing)
        s as SyncState.Syncing
        assertEquals(SyncStep.SYNCING_FOLDERS, s.currentStep)
        assertEquals(0.1f, s.stepProgress, 0.0001f)
        assertEquals("Syncing folders...", s.details)
        assertNull(s.item)
    }

    @Test
    fun beginItem_populates_item_progress_within_current_step() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "Syncing local notebooks...")

        reporter.beginItem(1, 3, "Meeting Notes")

        val s = reporter.state.value as SyncState.Syncing
        assertEquals(SyncStep.SYNCING_NOTEBOOKS, s.currentStep)
        assertNotNull(s.item)
        val item = s.item!!
        assertEquals(1, item.index)
        assertEquals(3, item.total)
        assertEquals("Meeting Notes", item.name)
    }

    @Test
    fun endItem_clears_item_progress_but_keeps_step() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "Syncing...")
        reporter.beginItem(2, 5, "Diary")

        reporter.endItem()

        val s = reporter.state.value as SyncState.Syncing
        assertEquals(SyncStep.SYNCING_NOTEBOOKS, s.currentStep)
        assertNull(s.item)
    }

    @Test
    fun beginItem_called_twice_replaces_item_not_accumulates() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "Syncing...")

        reporter.beginItem(1, 3, "First")
        reporter.beginItem(2, 3, "Second")

        val item = (reporter.state.value as SyncState.Syncing).item!!
        assertEquals(2, item.index)
        assertEquals("Second", item.name)
    }

    @Test
    fun beginStep_clears_stale_item_from_previous_step() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "...")
        reporter.beginItem(1, 1, "leftover")

        reporter.beginStep(SyncStep.FINALIZING, 0.9f, "Finalizing...")

        val s = reporter.state.value as SyncState.Syncing
        assertEquals(SyncStep.FINALIZING, s.currentStep)
        assertNull("beginStep must clear stale item from previous step", s.item)
    }

    @Test
    fun finishSuccess_emits_success_state_with_summary() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "...")
        val summary = SyncSummary(notebooksSynced = 2, notebooksDownloaded = 1, notebooksDeleted = 0, duration = 500L)

        reporter.finishSuccess(summary)

        val s = reporter.state.value
        assertTrue(s is SyncState.Success)
        assertEquals(summary, (s as SyncState.Success).summary)
    }

    @Test
    fun finishError_emits_error_state_preserving_current_step() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "...")

        reporter.finishError(SyncError.NETWORK_ERROR, canRetry = true)

        val s = reporter.state.value as SyncState.Error
        assertEquals(SyncError.NETWORK_ERROR, s.error)
        assertEquals(SyncStep.SYNCING_NOTEBOOKS, s.step)
        assertTrue(s.canRetry)
    }

    @Test
    fun finishError_when_not_syncing_uses_initializing_step() {
        val reporter = newReporter()

        reporter.finishError(SyncError.AUTH_ERROR, canRetry = false)

        val s = reporter.state.value as SyncState.Error
        assertEquals(SyncError.AUTH_ERROR, s.error)
        assertEquals(SyncStep.INITIALIZING, s.step)
    }

    @Test
    fun reset_returns_state_to_idle() {
        val reporter = newReporter()
        reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, 0.3f, "...")
        reporter.beginItem(1, 1, "X")

        reporter.reset()

        assertEquals(SyncState.Idle, reporter.state.value)
    }
}
