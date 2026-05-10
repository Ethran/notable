package com.ethran.notable.sync

/**
 * Centralized server path structure for WebDAV sync.
 * All server paths should be constructed here to prevent spelling mistakes
 * and make future structural changes easier.
 */
object SyncPaths {
    private const val ROOT = "notable"

    fun rootDir() = "/$ROOT"
    fun notebooksDir() = "/$ROOT/notebooks"
    fun tombstonesDir() = "/$ROOT/deletions"
    fun foldersFile() = "/$ROOT/folders.json"

    fun notebookDir(notebookId: String) = "/$ROOT/notebooks/$notebookId"
    fun manifestFile(notebookId: String) = "/$ROOT/notebooks/$notebookId/manifest.json"
    fun pagesDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/pages"
    fun pageFile(notebookId: String, pageId: String) =
        "/$ROOT/notebooks/$notebookId/pages/$pageId.json"

    fun imagesDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/images"
    fun imageFile(notebookId: String, imageName: String) =
        "/$ROOT/notebooks/$notebookId/images/$imageName"

    fun backgroundsDir(notebookId: String) = "/$ROOT/notebooks/$notebookId/backgrounds"
    fun backgroundFile(notebookId: String, bgName: String) =
        "/$ROOT/notebooks/$notebookId/backgrounds/$bgName"

    /**
     * Zero-byte tombstone file for a deleted notebook.
     * Presence of this file on the server means the notebook was deleted.
     * The server's own lastModified on the tombstone provides the deletion
     * timestamp needed for conflict resolution.
     */
    fun tombstone(notebookId: String) = "/$ROOT/deletions/$notebookId"
}
