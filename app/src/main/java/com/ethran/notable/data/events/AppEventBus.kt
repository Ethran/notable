package com.ethran.notable.data.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

interface AppEventBus {
    val events: SharedFlow<AppEvent>
    fun tryEmit(event: AppEvent)
    suspend fun emit(event: AppEvent)
}

@Singleton
class DefaultAppEventBus @Inject constructor() : AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    override fun tryEmit(event: AppEvent) {
        _events.tryEmit(event)
    }

    override suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }
}

