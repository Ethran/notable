package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPathsTest {

    @Test
    fun root_and_core_paths_are_stable() {
        assertEquals("/notable", SyncPaths.rootDir())
        assertEquals("/notable/notebooks", SyncPaths.notebooksDir())
        assertEquals("/notable/deletions", SyncPaths.tombstonesDir())
        assertEquals("/notable/folders.json", SyncPaths.foldersFile())
    }

    @Test
    fun notebook_scoped_paths_are_composed_correctly() {
        val notebookId = "nb-1"
        val pageId = "page-1"

        assertEquals("/notable/notebooks/nb-1", SyncPaths.notebookDir(notebookId))
        assertEquals("/notable/notebooks/nb-1/manifest.json", SyncPaths.manifestFile(notebookId))
        assertEquals("/notable/notebooks/nb-1/pages", SyncPaths.pagesDir(notebookId))
        assertEquals("/notable/notebooks/nb-1/pages/page-1.json", SyncPaths.pageFile(notebookId, pageId))
        assertEquals("/notable/deletions/nb-1", SyncPaths.tombstone(notebookId))
    }
}

