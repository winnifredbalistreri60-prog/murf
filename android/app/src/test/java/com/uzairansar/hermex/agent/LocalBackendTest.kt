package com.uzairansar.hermex.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackendTest {
    @Test
    fun autoPrefersEmbeddedWhenSupported() {
        assertEquals(LocalBackendKind.EMBEDDED, LocalBackends.resolve(LocalBackendKind.AUTO, embeddedSupported = true))
        assertEquals(LocalBackendKind.TERMUX, LocalBackends.resolve(LocalBackendKind.AUTO, embeddedSupported = false))
    }

    @Test
    fun explicitChoiceRespectedButEmbeddedFallsBackOnUnsupportedAbi() {
        assertEquals(LocalBackendKind.TERMUX, LocalBackends.resolve(LocalBackendKind.TERMUX, embeddedSupported = true))
        assertEquals(LocalBackendKind.EMBEDDED, LocalBackends.resolve(LocalBackendKind.EMBEDDED, embeddedSupported = true))
        assertEquals(LocalBackendKind.TERMUX, LocalBackends.resolve(LocalBackendKind.EMBEDDED, embeddedSupported = false))
    }

    @Test
    fun storageValuesRoundTrip() {
        for (k in LocalBackendKind.entries) assertEquals(k, LocalBackendKind.of(k.storage))
        assertEquals(LocalBackendKind.AUTO, LocalBackendKind.of(null))
        assertEquals(LocalBackendKind.AUTO, LocalBackendKind.of("garbage"))
    }

    @Test
    fun embeddedStatusUsesDownloadProgressUntilDone() {
        val j = JSONObject("""{"version":"1.4.0","embedded":true,"installed":false,"running":false,"installing":true,
            "rootfs":false,"disk_mb":-1,"free_mb":9000,
            "termux_progress":{"phase":"embedded","state":"running","pct":2,"step":"rootfs","msg":"Загрузка 10 из 30 МБ"},
            "progress":null,"health":null}""")
        val s = LocalEnv.parseStatus(j)
        assertTrue(s.embedded); assertTrue(s.installing); assertFalse(s.installed)
        assertEquals(2, s.pct); assertEquals("rootfs", s.step); assertEquals("Загрузка 10 из 30 МБ", s.stepMsg)
        assertEquals(9000, s.freeMb)
    }

    @Test
    fun innerProgressWinsAfterHostPhaseDone() {
        val j = JSONObject("""{"version":"1.4.0","embedded":true,"installed":false,"running":false,"installing":true,
            "termux_progress":{"state":"done","pct":5,"step":"inner","msg":"x"},
            "progress":{"state":"running","pct":40,"step":"apt","msg":"Установка пакетов"},"health":null}""")
        val s = LocalEnv.parseStatus(j)
        assertEquals(40, s.pct); assertEquals("apt", s.step)
    }

    @Test
    fun termuxStatusWithoutEmbeddedFlag() {
        val s = LocalEnv.parseStatus(JSONObject("""{"version":"1.3.3","installed":true,"running":true,"installing":false,"health":{"webui":true}}"""))
        assertFalse(s.embedded); assertTrue(s.running); assertTrue(s.webui)
    }
}
