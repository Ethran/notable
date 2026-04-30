package com.ethran.notable.ui

import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppEventUiBridge @Inject constructor(
    private val appEventBus: AppEventBus,
    private val snackDispatcher: SnackDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope
) {

    init {
        // Start listening for events
        appEventBus.events
            .onEach { event -> handleEvent(event) }
            .launchIn(appScope)
    }

    private fun handleEvent(event: AppEvent) {
        when (event) {
            is AppEvent.GenericError -> {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = event.message, duration = 4000))
            }

            is AppEvent.ExportProgress -> {
                val percent = if (event.total == 0) 0f else event.current.toFloat() / event.total
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        id = AppEvent.EXPORT_PROGRESS_ID,
                        text = "${event.prefix} ${event.current}/${event.total}",
                        progress = percent,
                    )
                )
            }

            is AppEvent.ExportCompleted -> {
                snackDispatcher.removeSnack(AppEvent.EXPORT_PROGRESS_ID)
                event.resultText?.let {
                    snackDispatcher.showOrUpdateSnack(SnackConf(text = it, duration = 2000))
                }
            }


            is AppEvent.ActionHint -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = event.message,
                        duration = event.duration
                    )
                )
            }

            is AppEvent.LogMessage -> {
                if (event.isError) {
                    io.shipbook.shipbooksdk.Log.e(event.reason, event.message)
                } else {
                    io.shipbook.shipbooksdk.Log.i(event.reason, event.message)
                }
                snackDispatcher.showOrUpdateSnack(SnackConf(text = event.message, duration = 3000))
            }


            is AppEvent.ImportProgress -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        id = AppEvent.IMPORT_PROGRESS_ID,
                        text = "${event.prefix} ${event.current}/${event.total}",
                        duration = null
                    )
                )
            }

            is AppEvent.ImportCompleted -> {
                snackDispatcher.removeSnack(AppEvent.IMPORT_PROGRESS_ID)
                event.resultText?.let {
                    snackDispatcher.showOrUpdateSnack(SnackConf(text = it, duration = 2000))
                }
            }

            is AppEvent.FileSaveError -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Failed to save ${event.fileName}: ${event.reason}",
                        duration = 5000
                    )
                )
            }

            is AppEvent.StrokeMigrationWarning -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Detected ${event.corruptedPoints} corrupted stroke entries during migration.",
                        duration = 5000
                    )
                )
            }

            AppEvent.StrokeMigrationPermissionMissing -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "No file permissions! Please grant file permissions and restart the app",
                        duration = 10000
                    )
                )
            }

            is AppEvent.StrokeMigrationProgress -> {
                val percent =
                    if (event.total == 0) 0.0 else (100.0 * event.migrated / event.total)
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        id = AppEvent.STROKE_MIGRATION_PROGRESS_ID,
                        text = "Migrating strokes: ${"%.1f".format(percent)}% (${event.migrated}/${event.total}) batch=${event.batchSize}",
                        duration = null
                    )
                )
            }

            AppEvent.StrokeMigrationCompleted -> {
                snackDispatcher.removeSnack(AppEvent.STROKE_MIGRATION_PROGRESS_ID)
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Stroke migration complete.", duration = 3000)
                )
            }

            is AppEvent.PreviewBackfillProgress -> {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        id = AppEvent.PREVIEW_BACKFILL_PROGRESS_ID,
                        text = "Generating previews ${event.current}/${event.total}",
                        duration = null
                    )
                )
            }

            is AppEvent.PreviewBackfillCompleted -> {
                snackDispatcher.removeSnack(AppEvent.PREVIEW_BACKFILL_PROGRESS_ID)
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Previews ready (${event.current}/${event.total})",
                        duration = 2000
                    )
                )
            }

            is AppEvent.DismissMessage -> {
                snackDispatcher.removeSnack(event.id)
            }
        }
    }
}
