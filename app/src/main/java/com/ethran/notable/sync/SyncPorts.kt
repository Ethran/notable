package com.ethran.notable.sync

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface WebDavClientFactoryPort {
    // Abstraction used by sync flow to avoid direct dependency on WebDAVClient construction.
    fun create(serverUrl: String, username: String, password: String): WebDAVClient
}

@Singleton
class WebDavClientFactoryAdapter @Inject constructor(
    // Shared OkHttpClient: one connection pool for all sync operations, not one per sync.
    private val httpClient: OkHttpClient
) : WebDavClientFactoryPort {
    override fun create(serverUrl: String, username: String, password: String): WebDAVClient {
        return WebDAVClient(serverUrl, username, password, httpClient)
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

@Module
@InstallIn(SingletonComponent::class)
object SyncHttpModule {
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    fun provideSyncOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
}
