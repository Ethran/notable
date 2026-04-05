package com.ethran.notable.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for an IO-bound CoroutineDispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

/**
 * Qualifier for a CoroutineScope that matches the application's lifecycle.
 * Use this for long-running tasks that should not be cancelled when a ViewModel or Fragment is destroyed.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

/**
 * Dagger module that provides global coroutine-related dependencies.
 *
 * This module is responsible for providing the [CoroutineDispatcher] used for IO-bound tasks
 * and the application-wide [CoroutineScope] used for long-running operations that should
 * outlive individual screens or ViewModels.
 */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    /**
     * Provides the [Dispatchers.IO] dispatcher.
     * Annotated with [Suppress] because Hilt usage is sometimes not detected by IDE inspections.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides an [ApplicationScope] that lives as long as the application.
     * Uses [SupervisorJob] so that a failure in one child coroutine doesn't cancel the whole scope.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}