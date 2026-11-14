package com.ethran.notable.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializedWriterTest {
    private data class State(val a: Int = 0, val b: Int = 0)

    private class Store {
        @Volatile var value = State()
        val writer = SerializedWriter(
            read = { value },
            write = { v -> delay(5); value = v },
        )
    }

    @Test
    fun concurrent_updates_to_different_fields_all_land() = runBlocking {
        val store = Store()
        (1..20).map { i ->
            async(Dispatchers.Default) {
                if (i % 2 == 0) store.writer.update { it.copy(a = it.a + 1) }
                else store.writer.update { it.copy(b = it.b + 1) }
            }
        }.awaitAll()

        assertEquals(State(a = 10, b = 10), store.value)
    }

    @Test
    fun replace_does_not_interleave_with_an_update() = runBlocking {
        val store = Store()
        val update = async(Dispatchers.Default) { store.writer.update { it.copy(a = 1) } }
        delay(1)
        val replace = async(Dispatchers.Default) { store.writer.replace(State(a = 1, b = 7)) }
        awaitAll(update, replace)

        assertEquals(1, store.value.a)
    }

    @Test
    fun unchanged_result_is_not_written_unless_asked() = runBlocking {
        var writes = 0
        val writer = SerializedWriter(read = { State() }, write = { _: State -> writes++ })
        writer.update { it }
        assertEquals(0, writes)
        writer.update(persistIfUnchanged = true) { it }
        assertEquals(1, writes)
    }
}
