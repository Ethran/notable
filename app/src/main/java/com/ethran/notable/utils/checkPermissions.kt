package com.ethran.notable.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Returns true if the app has "full file access" for your current storage model:
 * - < Android 11: WRITE_EXTERNAL_STORAGE is granted
 * - >= Android 11: MANAGE_EXTERNAL_STORAGE ("All files access") is granted
 */
fun hasFilePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}