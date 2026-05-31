package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPathsExtraTest {

    @Test
    fun image_and_background_paths_are_scoped_under_the_notebook() {
        val notebookId = "nb-42"

        assertEquals("/notable/notebooks/nb-42/images", SyncPaths.imagesDir(notebookId))
        assertEquals(
            "/notable/notebooks/nb-42/images/foo.png",
            SyncPaths.imageFile(notebookId, "foo.png")
        )

        assertEquals("/notable/notebooks/nb-42/backgrounds", SyncPaths.backgroundsDir(notebookId))
        assertEquals(
            "/notable/notebooks/nb-42/backgrounds/grid.pdf",
            SyncPaths.backgroundFile(notebookId, "grid.pdf")
        )
    }

    @Test
    fun every_notebook_scoped_path_lives_under_notebookDir() {
        val notebookId = "nb-xyz"
        val pageId = "page-1"
        val dir = SyncPaths.notebookDir(notebookId)

        listOf(
            SyncPaths.manifestFile(notebookId),
            SyncPaths.pagesDir(notebookId),
            SyncPaths.pageFile(notebookId, pageId),
            SyncPaths.imagesDir(notebookId),
            SyncPaths.imageFile(notebookId, "a.png"),
            SyncPaths.backgroundsDir(notebookId),
            SyncPaths.backgroundFile(notebookId, "bg.pdf"),
        ).forEach { path ->
            assertTrue("expected $path to start with $dir/", path.startsWith("$dir/"))
        }
    }

    @Test
    fun tombstone_lives_under_tombstones_dir_not_notebooks_dir() {
        val notebookId = "nb-deleted"
        val tombstone = SyncPaths.tombstone(notebookId)

        assertTrue(tombstone.startsWith(SyncPaths.tombstonesDir() + "/"))
        assertTrue(!tombstone.startsWith(SyncPaths.notebooksDir()))
    }
}
