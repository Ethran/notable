package com.ethran.notable.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncPathsAndroidTest {

    @Test(timeout = 10000)
    fun app_context_and_sync_paths_are_valid() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.ethran.notable", appContext.packageName)

        assertEquals("/notable", SyncPaths.rootDir())
        assertEquals("/notable/notebooks", SyncPaths.notebooksDir())
        assertEquals("/notable/deletions", SyncPaths.tombstonesDir())
        assertEquals("/notable/folders.json", SyncPaths.foldersFile())
    }
}

