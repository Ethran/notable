package com.ethran.notable.sync

import androidx.work.Data

sealed class SyncRequest {
    data object SyncAll : SyncRequest()
    data object ForceUpload : SyncRequest()
    data object ForceDownload : SyncRequest()
    data class UploadDeletion(val notebookId: String) : SyncRequest()
    data class SyncNotebook(val notebookId: String) : SyncRequest()
    data class SyncFromPageId(val pageId: String) : SyncRequest()

    val typeKey: String
        get() = when (this) {
            SyncAll -> TYPE_SYNC_ALL
            ForceUpload -> TYPE_FORCE_UPLOAD
            ForceDownload -> TYPE_FORCE_DOWNLOAD
            is UploadDeletion -> TYPE_UPLOAD_DELETION
            is SyncNotebook -> TYPE_SYNC_NOTEBOOK
            is SyncFromPageId -> TYPE_SYNC_FROM_PAGE_ID
        }

    /**
     * Returns a unique identifier for this request's parameters,
     * used for WorkManager unique work naming.
     */
    val identifier: String
        get() = when (this) {
            is UploadDeletion -> "notebookId:$notebookId"
            is SyncNotebook -> "notebookId:$notebookId"
            is SyncFromPageId -> "pageId:$pageId"
            else -> "default"
        }

    fun toDataBuilder(): Data.Builder {
        val builder = Data.Builder().putString(KEY_SYNC_TYPE, typeKey)
        when (this) {
            is UploadDeletion -> builder.putString(KEY_NOTEBOOK_ID, notebookId)
            is SyncNotebook -> builder.putString(KEY_NOTEBOOK_ID, notebookId)
            is SyncFromPageId -> builder.putString(KEY_PAGE_ID, pageId)
            else -> {}
        }
        return builder
    }

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_NOTEBOOK_ID = "notebook_id"
        const val KEY_PAGE_ID = "page_id"

        const val TYPE_SYNC_ALL = "SYNC_ALL"
        const val TYPE_FORCE_UPLOAD = "FORCE_UPLOAD"
        const val TYPE_FORCE_DOWNLOAD = "FORCE_DOWNLOAD"
        const val TYPE_UPLOAD_DELETION = "UPLOAD_DELETION"
        const val TYPE_SYNC_NOTEBOOK = "SYNC_NOTEBOOK"
        const val TYPE_SYNC_FROM_PAGE_ID = "SYNC_FROM_PAGE_ID"

        fun fromData(data: Data): SyncRequest? {
            val type = data.getString(KEY_SYNC_TYPE) ?: TYPE_SYNC_ALL
            return when (type) {
                TYPE_SYNC_ALL -> SyncAll
                TYPE_FORCE_UPLOAD -> ForceUpload
                TYPE_FORCE_DOWNLOAD -> ForceDownload
                TYPE_UPLOAD_DELETION -> data.getString(KEY_NOTEBOOK_ID)?.let { UploadDeletion(it) }
                TYPE_SYNC_NOTEBOOK -> data.getString(KEY_NOTEBOOK_ID)?.let { SyncNotebook(it) }
                TYPE_SYNC_FROM_PAGE_ID -> data.getString(KEY_PAGE_ID)?.let { SyncFromPageId(it) }
                else -> null
            }
        }
    }
}
