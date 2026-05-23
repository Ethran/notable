package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOrchestratorTest {

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
}
