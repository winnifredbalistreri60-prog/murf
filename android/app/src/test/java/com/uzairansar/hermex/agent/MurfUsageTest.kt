package com.uzairansar.hermex.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MurfUsageTest {
    private fun ev(s: String) = JSONObject(s)

    @Test fun dshStreamToTurnStats() {
        val a = DshUsageAccumulator()
        listOf(
            """{"type":"murf_run","provider":"mock","model":"deepseek-chat","_t":0}""",
            """{"type":"status","phase":"turn_start","turn":1,"_t":10}""",
            """{"type":"status","phase":"step_start","turn":1,"step":1,"_t":20}""",
            """{"type":"tool_call","callId":"c1","tool":"mcp__murf_phone__battery","_t":120}""",
            """{"type":"tool_result","callId":"c1","status":"completed","_t":170}""",
            """{"type":"status","phase":"step_end","turn":1,"step":1,"usage":{"inputTokens":200,"outputTokens":40,"totalTokens":1240,"cacheReadTokens":1000},"_t":170}""",
            """{"type":"status","phase":"step_start","turn":1,"step":2,"_t":180}""",
            """{"type":"text","text":"hi","_t":280}""",
            """{"type":"status","phase":"step_end","turn":1,"step":2,"usage":{"inputTokens":200,"outputTokens":40,"totalTokens":1240,"cacheReadTokens":1000},"_t":480}""",
            """{"type":"status","phase":"turn_end","turn":1,"_t":490}""",
        ).forEach { a.add(ev(it)) }
        val t = a.turns.single()
        assertEquals(2, t.steps)
        val u = t.usage!!
        assertEquals(400L, u.input); assertEquals(80L, u.output); assertEquals(2480L, u.sum); assertEquals(2000L, u.cacheRead)
        assertNull(u.cacheWrite)
        assertEquals(2000.0 * 100 / 2400, u.cacheHitPct!!, 0.001)
        assertEquals(50L, t.toolMs); assertEquals(100L, t.ttftMs)
        assertNotNull(MurfPrices.cost("deepseek-chat", u))
        assertNull(MurfPrices.cost("some-unknown-model", u))
    }

    @Test fun missingUsageMakesTurnUnknown() {
        val a = DshUsageAccumulator()
        listOf("""{"type":"status","phase":"turn_start"}""", """{"type":"status","phase":"step_start"}""",
            """{"type":"status","phase":"step_end"}""", """{"type":"status","phase":"turn_end"}""").forEach { a.add(ev(it)) }
        assertNull(a.turns.single().usage)
        assertNull(a.session().usage)
    }

    @Test fun parseModelLists() {
        assertEquals(listOf("a", "b"), MurfProviders.parseModels("""{"data":[{"id":"b"},{"id":"a"}]}"""))
        assertEquals(listOf("gemini-2.5-flash"), MurfProviders.parseModels("""{"models":[{"name":"models/gemini-2.5-flash"}]}"""))
    }

    @Test fun pathsInsideMurfDir() {
        assertEquals(true, PhoneControl.resolve("notes/a.txt").second)
        assertEquals("\$HOME/storage/shared/Download/MURF/etc/passwd", PhoneControl.resolve("../../etc/passwd").first)
        assertEquals(false, PhoneControl.resolve("/sdcard/DCIM/x.jpg").second)
        assertEquals(false, PhoneControl.resolve("/sdcard/Download/MURF/../DCIM/x").second)
        assertEquals(true, PhoneControl.resolve("/sdcard/Download/MURF/a.txt").second)
    }
}
