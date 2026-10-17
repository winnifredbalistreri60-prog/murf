package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.network.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MurfOutboxTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun outboxSurvivesRestartAndIsPerSession() {
        val dir = folder.newFolder("murf-outbox")
        val storeA = OfflineOutboxStore(dir, "https://srv\u0000session-a")
        val items = listOf(OutboxItem(id = "1", text = "первое"), OutboxItem(id = "2", text = "второе"))
        storeA.save(items)
        assertEquals(items, OfflineOutboxStore(dir, "https://srv\u0000session-a").load())
        assertEquals(emptyList<OutboxItem>(), OfflineOutboxStore(dir, "https://srv\u0000session-b").load())
        storeA.save(emptyList())
        assertEquals(emptyList<OutboxItem>(), storeA.load())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun corruptFileIsIgnored() {
        val dir = folder.newFolder("o2")
        val store = OfflineOutboxStore(dir, "k")
        store.save(listOf(OutboxItem(text = "x")))
        dir.listFiles()!!.single().writeText("{not json")
        assertEquals(emptyList<OutboxItem>(), store.load())
    }

    @Test
    fun onlyConnectFailuresAreQueued() {
        assertTrue(OfflineOutboxPolicy.shouldQueue(ApiError.Network(UnknownHostException("agent.example"))))
        assertTrue(OfflineOutboxPolicy.shouldQueue(ApiError.Network(IOException("wrap", ConnectException("refused")))))
        // A timeout may mean the server already got the message: never auto-resend (no duplicates).
        assertFalse(OfflineOutboxPolicy.shouldQueue(ApiError.Network(SocketTimeoutException("timeout"))))
        assertFalse(OfflineOutboxPolicy.shouldQueue(ApiError.Http(500, null)))
    }

    @Test
    fun telegramReadOnly403IsRecognised() {
        val error = ApiError.Http(403, """{"error":"Read-only imported sessions cannot be continued from WebUI"}""")
        assertTrue(ReadOnlySessionPolicy.isReadOnlyRejection(error))
        assertFalse(ReadOnlySessionPolicy.isReadOnlyRejection(ApiError.Http(403, """{"error":"Forbidden"}""")))
        assertFalse(ReadOnlySessionPolicy.isReadOnlyRejection(ApiError.Http(400, "read-only")))
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"))
            assertEquals("Это сессия из Telegram, только чтение. Начните новый чат.", ReadOnlySessionPolicy.message(null))
            assertEquals("Это сессия из Telegram, только чтение. Начните новый чат.", ReadOnlySessionPolicy.message("telegram"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
