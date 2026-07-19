package com.ethran.notable.sync

import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used by [SyncWorker] to reach the orchestrator and its collaborators from a
 * WorkManager-instantiated worker (which cannot use constructor injection).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncOrchestratorEntryPoint {
    fun syncOrchestrator(): SyncOrchestrator
    fun kvProxy(): KvProxy
    fun snackDispatcher(): SnackDispatcher
}
