package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurfMoreNavTest {
    @Test
    fun primaryMenuContainsDailyDestinations() {
        assertEquals(listOf("screen", "bots", "tasks", "kanban", "more"), MurfMoreNav.primaryIds())
    }

    @Test
    fun regroupingPreservesEveryPreviousDestination() {
        val reachable = MurfMoreNav.primaryIds().toSet() + MurfMoreNav.items().map { it.routeOrPanel }
        assertEquals(
            setOf("screen", "dsh", "bots", "devices", "local", "drive", "deploy", "tasks", "kanban", "more",
                "phone", "providers", "bottasks", "clis", "skills", "memory", "insights"),
            reachable,
        )
    }

    @Test
    fun groupedUtilitiesHaveNoDuplicatesAndUseCorrectNavigationKind() {
        val items = MurfMoreNav.items()
        assertEquals(items.size, items.map { it.routeOrPanel }.toSet().size)
        assertTrue(MurfMoreNav.groups().all { it.items.isNotEmpty() })
        assertEquals(setOf("tasks", "skills", "memory", "insights"),
            items.filter { it.kind == MurfMoreNav.Kind.Panel }.map { it.routeOrPanel }.toSet())
        assertTrue(items.any { it.routeOrPanel == "tasks" })
        assertTrue(items.any { it.routeOrPanel == "kanban" })
        for (id in listOf("devices", "local", "deploy", "providers", "clis")) {
            assertFalse(MurfMoreNav.primaryIds().contains(id))
        }
    }

    @Test
    fun findDshRemoteBaseRequiresSessionCookie() {
        val jar = okhttp3.CookieJar.NO_COOKIES
        assertEquals(null, findDshRemoteBase(jar, listOf("https://harness.example.com/")))
    }
}
