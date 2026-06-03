package com.ethran.notable.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.sync.SyncOrchestrator
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorMockKTest {
    @Test
    fun simpleMockKTest() {
        val orchestrator = mockk<SyncOrchestrator>(relaxed = true)
        assertNotNull(orchestrator)
    }
}
