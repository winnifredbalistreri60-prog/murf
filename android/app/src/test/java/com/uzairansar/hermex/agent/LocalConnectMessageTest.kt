package com.uzairansar.hermex.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class LocalConnectMessageTest {
    @Test
    fun connectExceptionMapsToWaitMessage() {
        val msg = LocalEnv.localConnectUserMessage(ConnectException("Failed to connect to /127.0.0.1:18080"))
        assertNotNull(msg)
        assertTrue(
            msg!!.contains("Сначала дождитесь") || msg.contains("Wait for the environment"),
        )
    }

    @Test
    fun nestedCauseMaps() {
        val wrapped = RuntimeException("boom", ConnectException("Connection refused"))
        assertNotNull(LocalEnv.localConnectUserMessage(wrapped))
    }

    @Test
    fun unrelatedStaysNull() {
        assertNull(LocalEnv.localConnectUserMessage(IllegalStateException("bad password")))
    }
}
