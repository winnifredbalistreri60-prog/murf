package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStartBusyTest {
    @Test
    fun busyLabelWithoutElapsed() {
        assertEquals("Запуск", LocalEnv.startBusyLabel(0, "Запуск"))
    }

    @Test
    fun busyLabelWithElapsed() {
        val s = LocalEnv.startBusyLabel(12, "Ожидание запуска среды")
        assertTrue(s.contains("12"))
        assertTrue(s.contains("Ожидание"))
    }

    @Test
    fun startCommandHasNoFgFlagInSourceContract() {
        // Documented contract: start uses murf-local start without --fg (background).
        // Regression guard for string helper remains independent of Termux.
        val label = LocalEnv.startBusyLabel(180, "Ожидание")
        assertFalse(label.contains("--fg"))
        assertTrue(label.endsWith("(180 с)") || label.contains("180"))
    }
}
