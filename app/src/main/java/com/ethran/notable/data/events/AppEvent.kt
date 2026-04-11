package com.ethran.notable.data.events

sealed interface AppEvent {
    data class StrokeMigrationWarning(val corruptedPoints: Int) : AppEvent
    data class FileSaveError(val fileName: String, val reason: String) : AppEvent
    data class GenericError(val message: String) : AppEvent

    data class ActionHint(val message: String, val duration: Int = 3000) : AppEvent
    data class LogMessage(val reason: String, val message: String, val isError: Boolean = true) : AppEvent
    
    data class ExportProgress(val current: Int, val total: Int, val prefix: String) : AppEvent
    data class ExportCompleted(val resultText: String?) : AppEvent
    
    data class ImportProgress(val current: Int, val total: Int, val prefix: String) : AppEvent
    data class ImportCompleted(val resultText: String?) : AppEvent

    data object StrokeMigrationPermissionMissing : AppEvent
    data class StrokeMigrationProgress(
        val migrated: Int,
        val total: Int,
        val batchSize: Int
    ) : AppEvent

    data object StrokeMigrationCompleted : AppEvent
    data class DismissMessage(val id: String) : AppEvent
    data class PreviewBackfillProgress(val current: Int, val total: Int) : AppEvent
    data class PreviewBackfillCompleted(val current: Int, val total: Int) : AppEvent

    companion object {
        const val STROKE_MIGRATION_PROGRESS_ID = "stroke_migration_progress"
        const val PREVIEW_BACKFILL_PROGRESS_ID = "preview_backfill_progress"
        const val EXPORT_PROGRESS_ID = "export_progress"
        const val IMPORT_PROGRESS_ID = "import_progress"
    }
}
