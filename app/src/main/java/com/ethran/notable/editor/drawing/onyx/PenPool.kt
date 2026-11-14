package com.ethran.notable.editor.drawing.onyx

import com.onyx.android.sdk.pen.NeoPen

/**
 * Keeps a few idle native pens so redraws don't create and destroy one per stroke.
 * A pen is checked out while in use, so no pen is shared between threads.
 */
class PenPool<K : Any, P : Any>(
    private val maxIdle: Int = 8,
    private val destroy: (P) -> Unit,
) {
    private val idle = ArrayDeque<Pair<K, P>>()

    /** Runs [block] with a pen for [key]; returns null if [create] fails. */
    fun <R> use(key: K, create: () -> P?, block: (P) -> R): R? {
        val pen = take(key) ?: create() ?: return null
        val result = try {
            block(pen)
        } catch (e: Throwable) {
            destroy(pen) // state unknown after a failure
            throw e
        }
        give(key, pen)
        return result
    }

    fun clear() {
        val old = synchronized(idle) { idle.toList().also { idle.clear() } }
        old.forEach { destroy(it.second) }
    }

    private fun take(key: K): P? = synchronized(idle) {
        val i = idle.indexOfLast { it.first == key }
        if (i >= 0) idle.removeAt(i).second else null
    }

    private fun give(key: K, pen: P) {
        val evicted = synchronized(idle) {
            idle.addLast(key to pen)
            if (idle.size > maxIdle) idle.removeFirst() else null
        }
        evicted?.let { destroy(it.second) }
    }
}

/** Shared pool for the pens we build ourselves (fountain V2, square). */
object NeoPenPool {
    val pool = PenPool<Any, NeoPen>(destroy = { it.destroy() })
}
