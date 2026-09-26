package com.ethran.notable

import android.app.Application
import android.util.Log
import com.onyx.android.sdk.base.utils.ResManager
import com.onyx.android.sdk.rx.RxManager
import dagger.hilt.android.HiltAndroidApp
import io.shipbook.shipbooksdk.ShipBook
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.content.edit

@HiltAndroidApp
class NotableApp : Application() {

    override fun onCreate() {
        Log.i("NotableApp", "onCreate START")
        super.onCreate()
        // Install first & synchronously so a crash during the rest of init is still caught.
        installCrashHandler()
        // Small prefs I/O, kept on-thread: a fast startup crash must still update last_start or the
        // next launch's crash-loop check misreads the interval.
        logCrashLoopSignalOnStart()
        // Pure cleanup (dir listing/sort/delete) — off the main thread to keep cold start cheap.
        Thread { pruneCrashFiles() }.start()
        RxManager.Builder.initAppContext(this)
        // penbrush reads its context from here (PenUtils static init, NeoPencilPen); without it the
        // first stroke render throws UninitializedPropertyAccessException.
        ResManager.init(this)
        checkHiddenApiBypass()
        Log.i("NotableApp", "onCreate FINISH")
    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    /**
     * Last-resort net for exceptions that escape a coroutine or the main thread. Without it they go
     * straight to the system "app has stopped" dialog with no telemetry. It records a crash-loop
     * marker, persists the stack to a local crash file (the durable record — survives when the
     * network/heap is dead), logs to logcat, then **chains to the previous handler** so normal
     * termination (and ShipBook's own async crash reporting) still happens.
     *
     * Installed here in `Application.onCreate` to cover early crashes, and **re-installed once more
     * right after `ShipBook.start()` in MainActivity** ([onShipBookStarted]) so our handler is
     * outermost and is guaranteed to write the durable file even if an SDK replaces the handler
     * without chaining. The [alreadyHandled] guard makes the crash work run once even when two of
     * our handlers end up in the chain.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // Nothing new to wrap (e.g. called twice with no SDK installing a handler in between).
        if (previous === installedHandler) return

        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            // If an inner copy of our handler already did the durable work for this exact throwable,
            // just chain — don't write a second file or re-commit the marker.
            if (alreadyHandled(throwable)) {
                previous?.uncaughtException(thread, throwable)
                return@UncaughtExceptionHandler
            }
            try {
                // Each durable step is guarded independently so one failing (e.g. commit() throwing
                // on a full disk, or an OOM building the file) can't skip the others. Durable-first:
                // the tiny crash-loop marker before the larger file write.
                try {
                    prefs().edit(commit = true) { putLong(KEY_LAST_CRASH, System.currentTimeMillis()) }
                } catch (t: Throwable) {
                    Log.e("NotableApp", "crash marker failed", t)
                }
                try {
                    writeCrashFile(thread, throwable)
                } catch (t: Throwable) {
                    Log.e("NotableApp", "crash file write failed", t)
                }
                // We do NOT call ShipBook here: its queue is async and the process dies as soon as we
                // chain below, so it wouldn't flush. ShipBook's own handler (which we chain to) reports
                // the exception; the local file above is our durable record. Logcat is synchronous.
                Log.e("NotableApp", "Uncaught exception on thread '${thread.name}'", throwable)
            } catch (t: Throwable) {
                // The handler must never throw.
                Log.e("NotableApp", "Crash handler itself failed", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        installedHandler = handler
    }

    /**
     * Call from MainActivity right after `ShipBook.start()`. Re-installs our handler on top of
     * ShipBook's — **at most once ever** (so repeated Activity recreation can't grow an unbounded
     * chain of wrapper handlers) — and flushes telemetry that couldn't reach ShipBook before it
     * started.
     */
    fun onShipBookStarted() {
        if (!reinstalledOverSdk) {
            reinstalledOverSdk = true
            installCrashHandler()
        }
        flushStartupTelemetry()
    }

    /**
     * One-shot: true if this launch looked like a crash loop (the previous run crashed within
     * [CRASH_LOOP_MS] of starting). The UI consumes it once to show a "copy crash logs" hint.
     */
    fun consumeCrashLoopDetected(): Boolean {
        val detected = crashLoopDetected
        crashLoopDetected = false
        return detected
    }

    /** Report telemetry gathered before ShipBook was up (it starts in MainActivity, after onCreate). */
    private fun flushStartupTelemetry() {
        val loopMs = pendingCrashLoopMs ?: return
        pendingCrashLoopMs = null
        ShipBook.getLogger("NotableApp")
            .w("Possible crash loop: previous run crashed $loopMs ms after launch")
    }

    /** Records the throwable we last handled so the same crash isn't persisted twice when two of
     *  our handlers sit in the chain (onCreate + post-ShipBook). Returns true if already handled. */
    private fun alreadyHandled(throwable: Throwable): Boolean {
        if (lastHandled === throwable) return true
        lastHandled = throwable
        return false
    }

    /** On startup, note if the last run crashed very soon after launching — a likely crash loop. */
    private fun logCrashLoopSignalOnStart() {
        try {
            val p = prefs()
            val lastStart = p.getLong(KEY_LAST_START, 0L)
            val lastCrash = p.getLong(KEY_LAST_CRASH, 0L)
            if (lastStart in 1..<lastCrash && (lastCrash - lastStart) < CRASH_LOOP_MS) {
                val delta = lastCrash - lastStart
                // ShipBook isn't started yet (it starts in MainActivity), so a ShipBook log here is
                // dropped. Stash it for onShipBookStarted() to report, and log to logcat now.
                pendingCrashLoopMs = delta
                crashLoopDetected = true
                Log.w("NotableApp", "Possible crash loop: previous run crashed $delta ms after launch")
            }
            p.edit { putLong(KEY_LAST_START, System.currentTimeMillis()) }
        } catch (t: Throwable) {
            Log.w("NotableApp", "crash-loop check failed", t)
        }
    }

    /**
     * Write one crash file. Kept minimal — no listing/sorting/deleting here (that runs at startup,
     * [pruneCrashFiles]); the death path only creates the new file. The filename is
     * `crash_<ts>_<seq>.txt`: the process-local counter disambiguates same-millisecond crashes
     * without the deprecated, recyclable `Thread.id`. The stack is streamed straight to the file
     * (no giant intermediate String) so this stays as OOM-safe as possible.
     */
    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val dir = File(filesDir, CRASH_DIR).apply { mkdirs() }
        val file = File(dir, "crash_${System.currentTimeMillis()}_${crashSeq.incrementAndGet()}.txt")
        PrintWriter(BufferedWriter(FileWriter(file))).use { pw ->
            pw.println("thread=${thread.name}")
            throwable.printStackTrace(pw)
        }
    }

    /** Cap the crashes/ dir at startup, off the death path. Keeps the newest [MAX_CRASH_FILES] by
     *  the timestamp embedded in the filename (more reliable than File.lastModified, which can be 0). */
    private fun pruneCrashFiles() {
        try {
            val files = File(filesDir, CRASH_DIR).listFiles() ?: return
            files.sortedByDescending { crashFileTimestamp(it.name) }
                .drop(MAX_CRASH_FILES)
                .forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w("NotableApp", "crash-file prune failed", t)
        }
    }

    /** Parse the `<ts>` out of `crash_<ts>_<seq>.txt`; 0 sorts unparsable names as oldest. */
    private fun crashFileTimestamp(name: String): Long =
        name.removePrefix("crash_").substringBefore('_').toLongOrNull() ?: 0L

    private fun prefs() = getSharedPreferences("crash_guard", MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_START = "last_start"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val CRASH_LOOP_MS = 5_000L
        private const val MAX_CRASH_FILES = 20

        /** Sub-directory of `filesDir` holding the crash files; read by the Settings crash-log viewer. */
        const val CRASH_DIR = "crashes"

        // The handler instance we last installed, so a re-install can tell "already ours" from "an
        // SDK replaced it". @Volatile because it is read/written across threads.
        @Volatile
        private var installedHandler: Thread.UncaughtExceptionHandler? = null

        // The throwable last persisted, to dedupe when two of our handlers are chained. @Volatile:
        // the crashing thread may not be the one that installed the handler.
        @Volatile
        private var lastHandled: Throwable? = null

        // One-shot guard: our handler is re-installed over ShipBook's exactly once, so repeated
        // Activity recreation can't grow an unbounded chain of wrapper handlers.
        @Volatile
        private var reinstalledOverSdk = false

        // Crash-loop delta detected at startup before ShipBook was up; reported once ShipBook starts.
        @Volatile
        private var pendingCrashLoopMs: Long? = null

        // Set when startup detects a likely crash loop; consumed once by the UI to show a hint.
        @Volatile
        private var crashLoopDetected = false

        // Process-local counter for unique crash filenames (avoids the deprecated/recyclable Thread.id).
        private val crashSeq = AtomicInteger(0)
    }
}
