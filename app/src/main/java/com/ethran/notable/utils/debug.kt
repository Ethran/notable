package com.ethran.notable.utils

import android.os.Looper
import io.shipbook.shipbooksdk.ShipBook


private val log = ShipBook.getLogger("debug")

fun logCallStack(reason: String, n: Int = 8) {
    val stackTrace = Thread.currentThread().stackTrace
        .drop(3) // Skip internal calls
        .take(n) // Limit depth
        .joinToString("\n") {
            "${it.className.removePrefix("com.ethran.notable.")}.${it.methodName} (${it.fileName}:${it.lineNumber})"
        }
    log.w("$reason Call stack:\n$stackTrace")
}

fun ensureNotMainThread(taskName: String) {
    if (Looper.getMainLooper().isCurrentThread) {
        log.w("$taskName running on main thread – consider dispatching to IO.")
    }
}