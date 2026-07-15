package com.ethran.notable.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.ethran.notable.data.getDbDirOrNull

/**
 * Returns true if the app has "full file access" for your current storage model:
 * - < Android 11: WRITE_EXTERNAL_STORAGE is granted
 * - >= Android 11: MANAGE_EXTERNAL_STORAGE ("All files access") is granted
 */
fun hasFilePermission(context: Context): Boolean {
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    if (hasPermission) return true

    // Skip permission check in automated tests to allow using in-memory components
    // without needing real storage permissions.
    return isRunningAndroidTest()
}

/**
 * Returns true only when the database directory is actually usable. The permission flag
 * alone is not enough: isExternalStorageManager() can report true while the directory
 * still can't be created or written (storage not mounted yet after boot, USB file
 * transfer mode, ...). Callers gating database access should use this instead of
 * [hasFilePermission] so failures route to the welcome/setup screen instead of crashing.
 */
fun hasUsableStorage(context: Context): Boolean {
    if (!hasFilePermission(context)) return false
    if (getDbDirOrNull() != null) return true
    // In instrumented tests external storage may be unavailable; keep the same bypass
    // hasFilePermission uses so in-memory components still work.
    return isRunningAndroidTest()
}

private fun isRunningAndroidTest(): Boolean {
    return try {
        val registryClass = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        val getInstrumentationMethod = registryClass.getMethod("getInstrumentation")
        getInstrumentationMethod.invoke(null) != null
    } catch (_: Exception) {
        false
    }
}
