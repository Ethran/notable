package com.ethran.notable.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface WebDavClientFactoryPort {
    // Abstraction used by sync flow to avoid direct dependency on WebDAVClient construction.
    fun create(serverUrl: String, username: String, password: String): WebDAVClient
}

@Singleton
class WebDavClientFactoryAdapter @Inject constructor() : WebDavClientFactoryPort {
    override fun create(serverUrl: String, username: String, password: String): WebDAVClient {
        return WebDAVClient(serverUrl, username, password)
    }
}

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncPortsModule {
    // Hilt consumes this binding at compile time; no explicit call site in app code.
    @Binds
    abstract fun bindWebDavClientFactory(impl: WebDavClientFactoryAdapter): WebDavClientFactoryPort
}

