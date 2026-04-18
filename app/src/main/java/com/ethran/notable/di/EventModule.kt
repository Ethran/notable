package com.ethran.notable.di

import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.events.DefaultAppEventBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EventModule {
    @Binds
    @Singleton
    abstract fun bindAppEventBus(
        defaultAppEventBus: DefaultAppEventBus
    ): AppEventBus
}

