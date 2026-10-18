package com.ethran.notable.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.io.PageContentRenderer
import com.ethran.notable.sync.FolderSyncService
import com.ethran.notable.sync.NotebookConflictResolution
import com.ethran.notable.sync.NotebookReconciliationService
import com.ethran.notable.sync.NotebookSyncService
import com.ethran.notable.sync.PageConflictResolution
import com.ethran.notable.sync.SyncForceService
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.sync.SyncPreflightService
import com.ethran.notable.sync.SyncProgressReporterImpl
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDavClientFactoryPort
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export and sync read pages from the database, so when [PageSaver] cannot write the editor's edits
 * there they stop before reading or sending anything, and leave the next run free to try again.
 */
@RunWith(AndroidJUnit4::class)
class UnsavedEditsStopReadersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pageSaver = mockk<PageSaver>().also { coEvery { it.saveAll() } returns false }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test(timeout = 30000)
    fun export_isCancelledWithoutReadingAnything_whenEditsCannotBeSaved() = runBlocking {
        val appRepository = mockk<AppRepository>(relaxed = true)
        val pageRepository = mockk<PageRepository>(relaxed = true)
        val bookRepository = mockk<BookRepository>(relaxed = true)
        val renderer = mockk<PageContentRenderer>(relaxed = true)
        val exportEngine = ExportEngine(
            context, appRepository, pageRepository, bookRepository, renderer,
            DefaultAppEventBus(), pageSaver, Dispatchers.IO, scope,
        )

        // A linked export, which would overwrite its file with what it read.
        val result = exportEngine.export(
            ExportTarget.Book("book"), ExportFormat.XOPP,
            ExportOptions(copyToClipboard = false, overwrite = true),
        )

        assertEquals(ExportEngine.EXPORT_CANCELLED_UNSAVED_EDITS, result)
        verify { listOf(appRepository, pageRepository, bookRepository, renderer) wasNot Called }
    }

    @Test(timeout = 30000)
    fun linkedExport_reportsThatItsFileWasNotUpdated_whenEditsCannotBeSaved() {
        val bookRepository = mockk<BookRepository>().also {
            coEvery { it.getById("book") } returns
                Notebook(id = "book", linkedExternalUri = "content://linked/book.xopp")
        }
        val eventBus = mockk<AppEventBus>(relaxed = true)
        val exportEngine = ExportEngine(
            context, mockk(relaxed = true), mockk(relaxed = true), bookRepository,
            mockk(relaxed = true), eventBus, pageSaver, Dispatchers.IO, scope,
        )

        exportEngine.exportToLinkedFileAsync("book")

        coVerify(timeout = 5000) {
            eventBus.emit(match {
                it is AppEvent.LogMessage && it.isError &&
                    it.message.contains(ExportEngine.EXPORT_CANCELLED_UNSAVED_EDITS)
            })
        }
    }

    @Test(timeout = 30000)
    fun everySync_stopsBeforeTransferring_andLetsTheNextOneRun_whenEditsCannotBeSaved() =
        runBlocking {
            val kvProxy = mockk<KvProxy>().also {
                coEvery { it.getSyncSettings() } returns SyncSettings(
                    syncEnabled = true, serverUrl = "https://sync.invalid", username = "u", password = "p",
                )
            }
            val preflight = mockk<SyncPreflightService>().also {
                coEvery { it.checkWifiConstraint() } returns AppResult.Success(Unit)
                coEvery { it.checkClockSkew(any()) } returns AppResult.Success(Unit)
            }
            val clientFactory = mockk<WebDavClientFactoryPort>().also {
                every { it.create(any(), any(), any()) } returns mockk(relaxed = true)
            }
            val folderSync = mockk<FolderSyncService>(relaxed = true)
            val notebookSync = mockk<NotebookSyncService>(relaxed = true)
            val forceSync = mockk<SyncForceService>(relaxed = true)
            val reconciliation = mockk<NotebookReconciliationService>(relaxed = true)
            val reporter = SyncProgressReporterImpl(scope)
            val orchestrator = SyncOrchestrator(
                mockk(relaxed = true), pageSaver, kvProxy, preflight, folderSync, notebookSync,
                forceSync, reconciliation, clientFactory, reporter, scope, Dispatchers.IO,
            )

            // Run one after another: each must let go of the sync lock, or the next one reports
            // "in progress" (or waits for it forever) instead of the save failure.
            val runs = listOf<Pair<String, suspend () -> AppResult<Unit, DomainError>>>(
                "full sync" to { orchestrator.syncAllNotebooks() },
                "notebook sync" to { orchestrator.syncNotebook("notebook") },
                "force upload" to { orchestrator.forceUploadAll() },
                "force download" to { orchestrator.forceDownloadAll() },
                "notebook conflict" to {
                    orchestrator.resolveNotebookConflict("notebook", NotebookConflictResolution.KEEP_LOCAL)
                },
                "page conflict" to {
                    orchestrator.resolvePageConflict("notebook", "page", PageConflictResolution.UPLOAD_DB)
                },
            )
            for ((name, run) in runs) {
                val result = withTimeout(5000) { run() }
                val error = (result as? AppResult.Error)?.error
                assertTrue("$name: $result", error is DomainError.SyncError && error.recoverable)
            }

            coVerify(exactly = runs.size) { pageSaver.saveAll() }
            verify { listOf(folderSync, notebookSync, forceSync, reconciliation) wasNot Called }
            assertTrue(reporter.state.value is SyncState.Error)
        }
}
