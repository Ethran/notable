package com.ethran.notable.sync

import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncPortsTest {

    @Test
    fun webDavClientFactoryAdapter_creates_client_instance() {
        val factory = WebDavClientFactoryAdapter()

        val client = factory.create(
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )

        assertNotNull(client)
    }
}

