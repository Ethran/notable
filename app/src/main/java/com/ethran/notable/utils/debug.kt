package com.ethran.notable.utils

import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log


fun logCallStack(reason: String, n: Int = 8) {
    val stackTrace = Thread.currentThread().stackTrace
        .drop(3) // Skip internal calls
        .take(n) // Limit depth
        .joinToString("\n") {
            "${it.className.removePrefix("com.ethran.notable.")}.${it.methodName} (${it.fileName}:${it.lineNumber})"
        }
    Log.w(TAG, "$reason Call stack:\n$stackTrace")
}