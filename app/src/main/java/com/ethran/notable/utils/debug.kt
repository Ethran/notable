package com.ethran.notable.utils

import android.os.Looper
import com.ethran.notable.BuildConfig
import io.shipbook.shipbooksdk.ShipBook


private val log = ShipBook.getLogger("debug")

private const val APP_PACKAGE = "com.ethran.notable"

/**
 * Formats [stack] into a compact, app-focused call stack for remote (ShipBook) logs.
 *
 * Keeps only frames in [APP_PACKAGE] — coroutine dispatchers, `BaseContinuationImpl.resumeWith`,
 * reflection, and other plumbing otherwise fill a fixed window and push the real caller out.
 * Excludes this file's own frames, then takes at most [n].
 * If no app frame is present (e.g. a pure coroutine resume with nothing of ours on the stack), it
 * falls back to the raw top-[n] frames so the log is never empty. It still cannot cross a
 * suspension boundary — pair it with a launch-site breadcrumb for async flows.
 */
internal fun formatAppCallStack(stack: Array<StackTraceElement>, n: Int = 8): String {
    fun format(e: StackTraceElement) =
        "${e.className.removePrefix("$APP_PACKAGE.")}.${e.methodName} (${e.fileName}:${e.lineNumber})"

    val appFrames = stack.filter { it.className.startsWith(APP_PACKAGE) && it.fileName != "debug.kt" }
    val chosen = if (appFrames.isNotEmpty()) appFrames else stack.drop(3)
    return chosen.take(n).joinToString("\n") { format(it) }
}

fun logCallStack(reason: String, n: Int = 8) {
    log.w("$reason Call stack:\n${formatAppCallStack(Thread.currentThread().stackTrace, n)}")
}

/** Fails in debug builds, logs in release ones, when [what] runs off the main thread. */
fun checkMainThread(what: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) return
    val message = "$what called off the main thread"
    if (BuildConfig.DEBUG) error(message) else log.e(message)
}

fun ensureNotMainThread(taskName: String) {
    if (Looper.getMainLooper().isCurrentThread) {
        log.w("$taskName running on main thread – consider dispatching to IO.")
    }
}