package com.uzairansar.hermex.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHandoffTest {
    @Test fun humanMineMeansHoldingAndAgentPaused() {
        val s = ScreenHandoff.parse(JSONObject("""{"ok":true,"supported":true,"holder":"human","mine":true,"since":1700000000.5}"""), ScreenHandoff.State())
        assertTrue(s.holding); assertTrue(s.agentPaused); assertFalse(s.heldByOther)
        assertEquals(1700000000500L, s.sinceMs)
    }

    @Test fun otherHumanHolds() {
        val s = ScreenHandoff.parse(JSONObject("""{"ok":true,"holder":"human","mine":false}"""), ScreenHandoff.State(holding = true))
        assertFalse(s.holding); assertTrue(s.heldByOther)
    }

    @Test fun agentHoldsClearsHolding() {
        val s = ScreenHandoff.parse(JSONObject("""{"ok":true,"holder":"agent","mine":false}"""), ScreenHandoff.State(holding = true, agentPaused = true))
        assertFalse(s.holding); assertFalse(s.agentPaused)
    }

    @Test fun unsupportedServer() {
        val s = ScreenHandoff.parse(JSONObject("""{"ok":true,"supported":false,"holder":"agent"}"""), ScreenHandoff.State(holding = true))
        assertEquals(false, s.supported); assertTrue(s.holding)
    }

    @Test fun handBackConsumedOnceAndExpires() {
        assertFalse(ScreenHandoff.consumeHandBack())
    }
}
