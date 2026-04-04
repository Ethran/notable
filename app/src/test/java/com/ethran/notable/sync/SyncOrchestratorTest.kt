package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOrchestratorTest {

    private fun networkFailure(): SyncResult = SyncResult.Failure(SyncError.NETWORK_ERROR)

    @Test
    fun syncResult_failure_keeps_error_value() {
        when (val result = networkFailure()) {
            is SyncResult.Failure -> assertEquals(SyncError.NETWORK_ERROR, result.error)
            SyncResult.Success -> error("Expected failure")
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
