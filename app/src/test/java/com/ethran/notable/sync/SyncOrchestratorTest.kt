package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOrchestratorTest {

    private class FakeReporter : SyncProgressReporter {
        override val state = kotlinx.coroutines.flow.MutableStateFlow<SyncState>(SyncState.Idle)

        override fun beginStep(step: SyncStep, stepProgress: Float, details: String) = Unit
        override fun beginItem(index: Int, total: Int, name: String, id: String?) = Unit
        override fun endItem() = Unit
        override fun reset() = Unit

        override fun finishSuccess(summary: SyncSummary) {
            state.value = SyncState.Success(summary)
        }

        override fun finishError(error: DomainError, canRetry: Boolean) {
            state.value = SyncState.Error(error, SyncStep.INITIALIZING, canRetry)
        }
    }

    private fun networkFailure(): AppResult<Unit, DomainError> = 
        AppResult.Error(DomainError.NetworkError("Network error"))

    @Test
    fun syncResult_failure_keeps_error_value() {
        when (val result = networkFailure()) {
            is AppResult.Error -> {
                val error = result.error as DomainError.NetworkError
                assertEquals("Network error", error.message)
            }
            is AppResult.Success -> error("Expected failure")
        }
    }

    @Test
    fun syncSummary_holds_counters_and_duration() {
        val summary = SyncSummary(
            notebooksSynced = 3,
            notebooksDownloaded = 2,
            notebooksDeleted = 1,
            duration = 1500L
        )

        assertEquals(3, summary.notebooksSynced)
        assertEquals(2, summary.notebooksDownloaded)
        assertEquals(1, summary.notebooksDeleted)
        assertEquals(1500L, summary.duration)
    }

    @Test
    fun finalizeSyncResult_noError_returns_success() {
        val reporter = FakeReporter()
        val summary = SyncSummary(
            notebooksSynced = 2,
            notebooksDownloaded = 0,
            notebooksDeleted = 0,
            duration = 200L
        )

        val result = finalizeSyncResult(reporter, summary, nonCriticalError = null)

        assertTrue(result is AppResult.Success)
        assertTrue(reporter.state.value is SyncState.Success)
        assertEquals(summary, (reporter.state.value as SyncState.Success).summary)
    }

    @Test
    fun finalizeSyncResult_withError_reports_failure() {
        val reporter = FakeReporter()
        val summary = SyncSummary(1, 0, 0, 50L)

        val result = finalizeSyncResult(
            reporter,
            summary,
            DomainError.NetworkError("upload failed")
        )

        assertTrue(result is AppResult.Error)
        assertTrue(reporter.state.value is SyncState.Error)
    }
}
