package com.ethran.notable.editor.drawing.onyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenPoolTest {
    private val created = mutableListOf<Int>()
    private val destroyed = mutableListOf<Int>()

    private fun pool(maxIdle: Int = 8) = PenPool<String, Int>(maxIdle) { destroyed += it }
    private fun create() = created.size.also { created += it }

    @Test
    fun reusesPenForSameKey() {
        val pool = pool()
        repeat(3) { pool.use("a", ::create) { } }
        assertEquals(1, created.size)
        assertTrue(destroyed.isEmpty())
    }

    @Test
    fun differentKeysGetDifferentPens() {
        val pool = pool()
        pool.use("a", ::create) { }
        pool.use("b", ::create) { }
        assertEquals(2, created.size)
    }

    @Test
    fun evictsOldestBeyondMaxIdle() {
        val pool = pool(maxIdle = 2)
        listOf("a", "b", "c").forEach { pool.use(it, ::create) { } }
        assertEquals(listOf(0), destroyed)
    }

    @Test
    fun failedUseDestroysPenAndRethrows() {
        val pool = pool()
        try {
            pool.use("a", ::create) { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf(0), destroyed)
        pool.use("a", ::create) { }
        assertEquals(2, created.size)
    }

    @Test
    fun nullFromFactoryReturnsNull() {
        assertNull(pool().use("a", { null }) { 1 })
    }

    @Test
    fun clearDestroysIdlePens() {
        val pool = pool()
        pool.use("a", ::create) { }
        pool.use("b", ::create) { }
        pool.clear()
        assertEquals(setOf(0, 1), destroyed.toSet())
    }

    @Test
    fun nestedUseOfSameKeyGetsSeparatePen() {
        val pool = pool()
        pool.use("a", ::create) { pool.use("a", ::create) { } }
        assertEquals(2, created.size)
    }
}
