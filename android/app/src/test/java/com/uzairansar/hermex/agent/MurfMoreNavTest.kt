package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurfMoreNavTest {
    @Test
    fun primaryOrderMatchesShippedIa() {
        assertEquals(
            listOf("screen", "dsh", "bots", "devices", "local", "drive", "deploy", "tasks", "kanban", "more"),
            MurfMoreNav.primaryIds(),
        )
    }

    @Test
    fun moreHoldsDemotedItemsNotPrimaryCore() {
        val more = MurfMoreNav.items().map { it.routeOrPanel }
        assertEquals(listOf("phone", "providers", "bottasks", "clis", "skills", "memory", "insights"), more)
        for (id in listOf("drive", "deploy", "local", "devices", "bots", "dsh")) {
            assertFalse(more.contains(id))
        }
    }

    @Test
    fun findDshRemoteBaseRequiresSessionCookie() {
        val jar = okhttp3.CookieJar.NO_COOKIES
        assertEquals(null, findDshRemoteBase(jar, listOf("https://harness.example.com/")))
    }
}
