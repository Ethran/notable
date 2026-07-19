package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SyncPreflightService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kvProxy: KvProxy
) {
    suspend fun checkWifiConstraint(): AppResult<Unit, DomainError> {
        val settings = kvProxy.getSyncSettings()
        if (!settings.wifiOnly) return AppResult.Success(Unit)

        return if (ConnectivityChecker(context).isUnmeteredConnected()) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(DomainError.SyncWifiRequired)
        }
    }

    fun checkClockSkew(client: WebDAVClient): AppResult<Unit, DomainError> {
        val serverTime = client.getServerTime()
            ?: return AppResult.Error(DomainError.NetworkError("Could not retrieve server time"))

        val skewMs = System.currentTimeMillis() - serverTime
        return if (abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
            AppResult.Error(DomainError.SyncClockSkew(skewMs / 1000))
        } else {
            AppResult.Success(Unit)
        }
    }

    fun ensureServerDirectories(client: WebDAVClient): AppResult<Unit, DomainError> {
        val dirs = listOf(
            SyncPaths.rootDir(),
            SyncPaths.notebooksDir(),
            SyncPaths.tombstonesDir()
        )
        for (dir in dirs) {
            val present = client.exists(dir).onFailure { return AppResult.Error(it) }
            if (!present) {
                client.createCollection(dir).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    companion object {
        private const val CLOCK_SKEW_THRESHOLD_MS = 30_000L
    }
}
