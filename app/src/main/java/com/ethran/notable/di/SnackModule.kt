package com.ethran.notable.di

import com.ethran.notable.ui.DefaultSnackDispatcher
import com.ethran.notable.ui.SnackDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SnackModule {
    @Binds
    @Singleton
    abstract fun bindSnackDispatcher(
        defaultSnackDispatcher: DefaultSnackDispatcher
    ): SnackDispatcher
}

