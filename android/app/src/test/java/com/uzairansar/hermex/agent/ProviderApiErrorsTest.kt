package com.uzairansar.hermex.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProviderApiErrorsTest {
    @Test
    fun rejectsUnauthorizedWithoutLeakingKey() {
        val msg = ProviderApiErrors.userMessage(RuntimeException("Key rejected (HTTP 401) sk-abcdefghijklmnop"))
        assertTrue(msg.contains("401") || msg.contains("403") || msg.contains("ключ", ignoreCase = true) || msg.contains("key", ignoreCase = true))
        assertFalse(msg.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun networkErrorsAreHonest() {
        val host = ProviderApiErrors.userMessage(UnknownHostException("api.example.com"))
        assertTrue(host.contains("сеть", ignoreCase = true) || host.contains("Network", ignoreCase = true) || host.contains("internet", ignoreCase = true))
        val timeout = ProviderApiErrors.userMessage(SocketTimeoutException("timeout"))
        assertTrue(timeout.contains("сеть", ignoreCase = true) || timeout.contains("Network", ignoreCase = true) || timeout.contains("URL", ignoreCase = true))
    }

    @Test
    fun invalidUrlMessage() {
        val msg = ProviderApiErrors.userMessage(IllegalArgumentException("Неверный адрес API"))
        assertTrue(msg.contains("адрес", ignoreCase = true) || msg.contains("URL", ignoreCase = true) || msg.contains("Invalid", ignoreCase = true))
    }
}
