package com.ethran.notable.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn


// Helper function to achieve time-based chunking
fun <T> Flow<T>.chunked(timeoutMillisSelector: Long): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            val start = System.currentTimeMillis()
            val received = channel.receiveCatching().getOrNull() ?: break
            buffer.add(received)

            while (System.currentTimeMillis() - start < timeoutMillisSelector) {
                val next = channel.tryReceive().getOrNull() ?: continue
                buffer.add(next)
            }
            emit(buffer.toList())
            buffer.clear()
        }
    }
}

fun <T : Any> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = flow {
    var prev: T? = null
    this@withPrevious.collect {
        emit(prev to it)
        prev = it
    }
}