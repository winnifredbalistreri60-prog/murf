package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxSessionActionTest {
    @Test
    fun backgroundNeverOpensSession() {
        assertEquals("2", Termux.sessionAction(background = true))
        assertEquals("2", Termux.sessionAction(background = true, openSession = false))
    }

    @Test
    fun visibleOpensSessionByDefault() {
        assertEquals("0", Termux.sessionAction(background = false))
        assertEquals("0", Termux.sessionAction(background = false, openSession = true))
    }

    @Test
    fun runSessionCanStayHidden() {
        assertEquals("2", Termux.sessionAction(background = false, openSession = false))
    }
}
