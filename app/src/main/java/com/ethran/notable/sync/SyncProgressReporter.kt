package com.ethran.notable.sync

import com.ethran.notable.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

interface SyncProgressReporter {
    val state: StateFlow<SyncState>

    fun beginStep(step: SyncStep, stepProgress: Float, details: String)
    fun beginItem(index: Int, total: Int, name: String)
    fun endItem()
    fun finishSuccess(summary: SyncSummary)
    fun finishError(error: SyncError, canRetry: Boolean)
    fun reset()
}

@Singleton
class SyncProgressReporterImpl @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope
) : SyncProgressReporter {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    override fun beginStep(step: SyncStep, stepProgress: Float, details: String) {
        _state.value = SyncState.Syncing(
            currentStep = step,
            stepProgress = stepProgress,
            details = details,
            item = null
        )
    }

    override fun beginItem(index: Int, total: Int, name: String) {
        _state.update { current ->
            when (current) {
                is SyncState.Syncing -> current.copy(item = ItemProgress(index, total, name))
                else -> current
            }
        }
    }

    override fun endItem() {
        _state.update { current ->
            when (current) {
                is SyncState.Syncing -> current.copy(item = null)
                else -> current
            }
        }
    }

    override fun finishSuccess(summary: SyncSummary) {
        _state.value = SyncState.Success(summary)
    }

    override fun finishError(error: SyncError, canRetry: Boolean) {
        val step = (_state.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
        _state.value = SyncState.Error(error, step, canRetry)
    }

    override fun reset() {
        _state.value = SyncState.Idle
    }
}

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncProgressReporterModule {
    @Binds
    abstract fun bindSyncProgressReporter(impl: SyncProgressReporterImpl): SyncProgressReporter
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncProgressReporterEntryPoint {
    fun syncProgressReporter(): SyncProgressReporter
}

