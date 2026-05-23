package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLoggerTest {

    @Test
    fun clear_resets_logs() {
        SyncLogger.clear()
        SyncLogger.i("sync", "first")
        assertTrue(SyncLogger.logs.value.isNotEmpty())

        SyncLogger.clear()

        assertTrue(SyncLogger.logs.value.isEmpty())
    }

    @Test
    fun log_capped_to_max_entries_and_preserves_tail() {
        SyncLogger.clear()

        for (i in 0 until 55) {
            SyncLogger.i("sync", "message-$i")
        }

        val logs = SyncLogger.logs.value
        assertEquals(50, logs.size)
        assertEquals("message-5", logs.first().message)
        assertEquals("message-54", logs.last().message)
    }

    @Test
    fun log_levels_are_recorded_in_order() {
        SyncLogger.clear()

        SyncLogger.i("sync", "info")
        SyncLogger.w("sync", "warn")
        SyncLogger.e("sync", "error")

        val logs = SyncLogger.logs.value
        assertEquals(3, logs.size)
        assertEquals(SyncLogger.LogLevel.INFO, logs[0].level)
        assertEquals(SyncLogger.LogLevel.WARNING, logs[1].level)
        assertEquals(SyncLogger.LogLevel.ERROR, logs[2].level)
    }
}

