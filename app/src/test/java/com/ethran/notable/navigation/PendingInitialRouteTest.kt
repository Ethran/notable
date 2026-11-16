package com.ethran.notable.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInitialRouteTest {
    @Test
    fun `route is pushed once on a library-only stack`() {
        val pending = PendingInitialRoute("editor/p")

        assertEquals("editor/p", pending.consume(libraryOnly = true))
        assertNull(pending.consume(libraryOnly = true))
    }

    @Test
    fun `route already consumed is not pushed again on a library-only stack`() {
        val pending = PendingInitialRoute("editor/p")
        pending.consume(libraryOnly = true)

        assertNull(pending.consume(libraryOnly = true))
    }

    @Test
    fun `a restored back stack is never pushed onto, and the route is dropped`() {
        val pending = PendingInitialRoute("editor/p")

        assertNull(pending.consume(libraryOnly = false))
        assertNull(pending.consume(libraryOnly = true))
    }

    @Test
    fun `no route means nothing to push`() {
        assertNull(PendingInitialRoute(null).consume(libraryOnly = true))
    }
}
