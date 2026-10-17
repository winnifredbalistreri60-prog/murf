package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ToolCall
import com.uzairansar.hermex.core.network.ToolStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtaskPolicyTest {
    private fun args(json: String): Map<String, kotlinx.serialization.json.JsonElement> = Json.parseToJsonElement(json).jsonObject

    @Test
    fun detectsSubagentTools() {
        assertTrue(SubtaskPolicy.isSubtaskTool("delegate_task"))
        assertTrue(SubtaskPolicy.isSubtaskTool("Task"))
        assertTrue(SubtaskPolicy.isSubtaskTool("spawn_subagent"))
        assertFalse(SubtaskPolicy.isSubtaskTool("terminal"))
        assertFalse(SubtaskPolicy.isSubtaskTool("read_file"))
        assertFalse(SubtaskPolicy.isSubtaskTool(null))
    }

    @Test
    fun titlesFromGoalsAndBatches() {
        assertEquals("собрать отчёт", SubtaskPolicy.title("delegate_task", args("""{"goal":"собрать отчёт"}"""), null))
        val batch = args("""{"tasks":[{"goal":"A"},{"goal":"B"},{"goal":"C"}]}""")
        assertEquals(listOf("A", "B", "C"), SubtaskPolicy.goals(batch))
        assertTrue(SubtaskPolicy.title("delegate_task", batch, null).endsWith("A"))
        assertEquals("delegate_task", SubtaskPolicy.title("delegate_task", null, null))
    }

    @Test
    fun formatsElapsed() {
        assertEquals("1:05", SubtaskPolicy.formatElapsed(65))
        assertEquals("1:02:03", SubtaskPolicy.formatElapsed(3723))
        assertEquals(10L, SubtaskPolicy.elapsedSeconds(1_000, 11_000, 99_000))
        assertEquals(98L, SubtaskPolicy.elapsedSeconds(1_000, null, 99_000))
        assertEquals(7L, SubtaskPolicy.elapsedSeconds(1_000, null, 99_000, durationSec = 7.4))
    }

    @Test
    fun trackerFollowsToolEventsAndMergesHistory() {
        val start = ToolStreamEvent(name = "delegate_task", args = args("""{"goal":"проверить тесты"}"""), toolCallId = "x1")
        var tasks = ChatTaskTracker.started(emptyList(), start, 1_000)
        tasks = ChatTaskTracker.started(tasks, ToolStreamEvent(name = "terminal"), 1_100)
        assertEquals(1, tasks.size)
        assertEquals(TaskStatus.Running, tasks[0].status)
        tasks = ChatTaskTracker.completed(tasks, ToolStreamEvent(name = "delegate_task", toolCallId = "x1", duration = 12.0), 13_000)
        assertEquals(TaskStatus.Done, tasks[0].status)
        assertEquals(12.0, tasks[0].durationSec!!, 0.0)
        val failedStart = ChatTaskTracker.started(tasks, ToolStreamEvent(name = "delegate_task", args = args("""{"goal":"B"}""")), 20_000)
        val stopped = ChatTaskTracker.finishAll(failedStart, TaskStatus.Stopped, 30_000)
        assertEquals(TaskStatus.Stopped, stopped.last().status)
        val history = listOf(
            ToolCall(id = "x1", name = "delegate_task", args = args("""{"goal":"проверить тесты"}""")),
            ToolCall(id = "h2", name = "delegate_task", args = args("""{"goal":"старое"}"""), result = JsonPrimitive("ok"), isError = true),
            ToolCall(id = "h3", name = "read_file"),
        )
        val merged = ChatTaskTracker.merged(stopped, history)
        assertEquals(3, merged.size)
        val old = merged.first { it.id == "h2" }
        assertEquals(TaskStatus.Failed, old.status)
        assertEquals("ok", old.detail)
        val ordered = SubtaskPolicy.ordered(merged + ChatSubtask(id = "live", title = "идёт", status = TaskStatus.Running, startedAtMs = 50_000))
        assertEquals("live", ordered.first().id)
    }
}
