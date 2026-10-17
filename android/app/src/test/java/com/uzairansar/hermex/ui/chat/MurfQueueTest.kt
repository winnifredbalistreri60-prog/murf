package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MurfQueueTest {
    private val a = QueuedDraft("a", emptyList(), id = "1")
    private val b = QueuedDraft("b", emptyList(), id = "2")
    private val c = QueuedDraft("c", emptyList(), id = "3")

    @Test fun removeKeepsOrder() {
        assertEquals(listOf("a", "c"), MurfQueueOps.remove(listOf(a, b, c), "2").map { it.text })
        assertEquals(3, MurfQueueOps.remove(listOf(a, b, c), "nope").size)
    }

    @Test fun moveToFront() {
        assertEquals(listOf("c", "a", "b"), MurfQueueOps.moveToFront(listOf(a, b, c), "3").map { it.text })
        assertEquals(listOf("a", "b", "c"), MurfQueueOps.moveToFront(listOf(a, b, c), "x").map { it.text })
    }

    @Test fun replaceText() {
        assertEquals(listOf("a", "B", "c"), MurfQueueOps.replaceText(listOf(a, b, c), "2", "B").map { it.text })
    }

    @Test fun legacyQueuedDraftJsonGetsId() {
        val decoded = com.uzairansar.hermex.core.network.HermesJson.decodeFromString<QueuedDraft>("""{"text":"old","attachments":[]}""")
        assertEquals("old", decoded.text)
        assert(decoded.id.isNotBlank())
    }
}
