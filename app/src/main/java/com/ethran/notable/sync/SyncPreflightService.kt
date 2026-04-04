package com.ethran.notable.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPreflightService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialManager: CredentialManager
) {
    fun checkWifiConstraint(): Boolean {
        val settings = credentialManager.settings.value
        return if (settings.wifiOnly && !ConnectivityChecker(context).isUnmeteredConnected()) {
            SyncLogger.i("SyncPreflightService", "WiFi-only sync enabled but not on WiFi, skipping")
            false
        } else {
            true
        }
    }

    fun checkClockSkew(webdavClient: WebDAVClient): Long? {
        val serverTime = webdavClient.getServerTime() ?: return null
        return System.currentTimeMillis() - serverTime
    }

    fun ensureServerDirectories(webdavClient: WebDAVClient) {
        if (!webdavClient.exists(SyncPaths.rootDir())) {
            webdavClient.createCollection(SyncPaths.rootDir())
        }
        if (!webdavClient.exists(SyncPaths.notebooksDir())) {
            webdavClient.createCollection(SyncPaths.notebooksDir())
        }
        if (!webdavClient.exists(SyncPaths.tombstonesDir())) {
            webdavClient.createCollection(SyncPaths.tombstonesDir())
        }
    }
}
