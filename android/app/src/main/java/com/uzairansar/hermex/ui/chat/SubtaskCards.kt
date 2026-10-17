package com.uzairansar.hermex.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.agent.tr
import com.uzairansar.hermex.ui.theme.HermexSurfaceLevel
import com.uzairansar.hermex.ui.theme.hermexGlass
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Состояние подзадачи / задачи (подагент Hermes, шаг CLI, запуск бота). */
enum class TaskStatus {
    Running, Waiting, Done, Failed, Stopped;

    val isActive: Boolean get() = this == Running || this == Waiting

    companion object {
        fun from(raw: String?): TaskStatus = when (raw?.lowercase()) {
            "running", "pending", "in_progress" -> Running
            "waiting" -> Waiting
            "done", "completed", "ok", "success" -> Done
            "stopped", "cancelled", "canceled" -> Stopped
            "failed", "error" -> Failed
            else -> Running
        }
    }
}

/** Подзадача основного чата (delegate_task Hermes) — для карточек и панели «Задачи». */
data class ChatSubtask(
    val id: String,
    val title: String,
    val detail: String? = null,
    val status: TaskStatus = TaskStatus.Running,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val durationSec: Double? = null,
    val goals: List<String> = emptyList(),
)

object SubtaskPolicy {
    private val subtaskNames = setOf("delegate_task", "delegate", "subagent", "spawn_subagent", "spawn_agent", "task", "agent")

    /** Инструмент, который запускает отдельного агента (подзадачу), а не обычный шаг. */
    fun isSubtaskTool(name: String?): Boolean {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.isEmpty()) return false
        return n in subtaskNames || "delegate" in n || "subagent" in n || "sub-agent" in n
    }

    /** Цели подзадач из аргументов delegate_task: goal / tasks[].goal / prompt / description. */
    fun goals(args: Map<String, JsonElement>?): List<String> {
        if (args == null) return emptyList()
        fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val out = mutableListOf<String>()
        listOf("goal", "prompt", "description", "task").firstNotNullOfOrNull { str(args[it]) }?.let(out::add)
        (args["tasks"] as? JsonArray)?.forEach { item ->
            when (item) {
                is JsonObject -> listOf("goal", "prompt", "description", "task").firstNotNullOfOrNull { str(item[it]) }?.let(out::add)
                else -> str(item)?.let(out::add)
            }
        }
        return out
    }

    fun title(name: String?, args: Map<String, JsonElement>?, preview: String?): String {
        val g = goals(args)
        return when {
            g.size > 1 -> tr("${g.size} подзадачи: ", "${g.size} subtasks: ") + g.first()
            g.size == 1 -> g.first()
            !preview.isNullOrBlank() -> preview.lineSequence().first { it.isNotBlank() }.trim()
            else -> name?.takeIf { it.isNotBlank() } ?: tr("Подзадача", "Subtask")
        }.let { if (it.length > 140) it.take(139) + "…" else it }
    }

    /** 9 с · 1:05 · 1:02:03 */
    fun formatElapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m, sec)
            m > 0 -> "%d:%02d".format(m, sec)
            else -> tr("$sec с", "${sec}s")
        }
    }

    fun elapsedSeconds(startedAtMs: Long?, endedAtMs: Long?, nowMs: Long, durationSec: Double? = null): Long? = when {
        durationSec != null -> durationSec.toLong()
        startedAtMs == null -> null
        else -> ((endedAtMs ?: nowMs) - startedAtMs) / 1000
    }

    /** Сначала идущие, затем завершённые (новые сверху). */
    fun ordered(tasks: List<ChatSubtask>): List<ChatSubtask> =
        tasks.sortedWith(compareBy<ChatSubtask> { if (it.status.isActive) 0 else 1 }.thenByDescending { it.startedAtMs ?: 0L })
}

@Composable
fun taskStatusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.Running -> tr("идёт", "running")
    TaskStatus.Waiting -> tr("ждёт ответа", "waiting")
    TaskStatus.Done -> tr("готово", "done")
    TaskStatus.Failed -> tr("ошибка", "failed")
    TaskStatus.Stopped -> tr("остановлено", "stopped")
}

@Composable
fun taskStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.Running -> MaterialTheme.colorScheme.primary
    TaskStatus.Waiting -> MaterialTheme.colorScheme.tertiary
    TaskStatus.Done -> MaterialTheme.colorScheme.secondary
    TaskStatus.Failed -> MaterialTheme.colorScheme.error
    TaskStatus.Stopped -> MaterialTheme.colorScheme.outline
}

@Composable
fun TaskStatusPill(status: TaskStatus) {
    val color = taskStatusColor(status)
    Text(
        taskStatusLabel(status),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

/** Тикающее время выполнения (раз в секунду, пока задача идёт). */
@Composable
fun rememberElapsedText(status: TaskStatus, startedAtMs: Long?, endedAtMs: Long?, durationSec: Double? = null): String? {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, startedAtMs) {
        while (status.isActive && startedAtMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val sec = SubtaskPolicy.elapsedSeconds(startedAtMs, if (status.isActive) null else (endedAtMs ?: nowMs), nowMs,
        durationSec.takeIf { !status.isActive })
    return sec?.let(SubtaskPolicy::formatElapsed)
}

/**
 * Свёрнутая карточка подзадачи: значок, заголовок, статус, время; содержимое — со своей прокруткой
 * (не растягивает основной чат).
 */
@Composable
fun SubtaskCard(
    title: String,
    status: TaskStatus,
    modifier: Modifier = Modifier,
    startedAtMs: Long? = null,
    endedAtMs: Long? = null,
    durationSec: Double? = null,
    subtitle: String? = null,
    startsExpanded: Boolean = false,
    maxBodyHeight: Dp = 260.dp,
    stateKey: String = title,
    leading: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(startsExpanded) }
    val elapsed = rememberElapsedText(status, startedAtMs, endedAtMs, durationSec)
    val accent = taskStatusColor(status)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hermexGlass(shape = RoundedCornerShape(10.dp), castsShadow = false, surfaceLevel = HermexSurfaceLevel.Raised)
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("subtask_card"),
        verticalArrangement = Arrangement.spacedBy(if (expanded) 8.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = body != null) { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading() else Text("\u2442", modifier = Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium,
                color = accent, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            elapsed?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
            }
            TaskStatusPill(status)
            if (body != null) {
                Text(if (expanded) "\u2303" else "\u2304", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            }
        }
        if (expanded && body != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = body,
                )
            }
        }
    }
}

@Composable
fun SubtaskDetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface)
}

/** Панель «Задачи» текущего чата: идущие и завершённые подзадачи. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTasksSheet(tasks: List<ChatSubtask>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ordered = SubtaskPolicy.ordered(tasks)
            val running = ordered.count { it.status.isActive }
            Text(tr("Задачи чата", "Chat tasks"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Идёт: $running · завершено: ${ordered.size - running}", "Running: $running · finished: ${ordered.size - running}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ordered.isEmpty()) {
                Text(tr("Подзадач в этом чате пока нет.", "No subtasks in this chat yet."), style = MaterialTheme.typography.bodyMedium)
            }
            ordered.forEach { t ->
                SubtaskCard(
                    title = t.title, status = t.status, startedAtMs = t.startedAtMs, endedAtMs = t.endedAtMs,
                    durationSec = t.durationSec, stateKey = "sheet-" + t.id,
                    body = if (t.goals.size > 1 || !t.detail.isNullOrBlank()) {
                        {
                            if (t.goals.size > 1) t.goals.forEachIndexed { i, g -> SubtaskDetailText("${i + 1}. $g") }
                            t.detail?.takeIf { it.isNotBlank() }?.let { SubtaskDetailText(it) }
                        }
                    } else null,
                )
            }
        }
    }
}

/** Живые подзадачи основного чата по событиям потока tool / tool_complete. */
object ChatTaskTracker {
    private const val MAX = 100

    private fun keyOf(e: com.uzairansar.hermex.core.network.ToolStreamEvent): String? =
        e.toolCallId ?: e.toolUseId ?: e.callId ?: e.tid ?: e.id

    fun started(tasks: List<ChatSubtask>, e: com.uzairansar.hermex.core.network.ToolStreamEvent, nowMs: Long): List<ChatSubtask> {
        if (!SubtaskPolicy.isSubtaskTool(e.name)) return tasks
        val id = keyOf(e) ?: "sub-$nowMs-${tasks.size}"
        if (tasks.any { it.id == id }) return tasks
        val task = ChatSubtask(
            id = id,
            title = SubtaskPolicy.title(e.name, e.args, e.preview),
            status = TaskStatus.Running,
            startedAtMs = nowMs,
            goals = SubtaskPolicy.goals(e.args),
        )
        return (tasks + task).takeLast(MAX)
    }

    fun completed(tasks: List<ChatSubtask>, e: com.uzairansar.hermex.core.network.ToolStreamEvent, nowMs: Long): List<ChatSubtask> {
        if (!SubtaskPolicy.isSubtaskTool(e.name)) return tasks
        val key = keyOf(e)
        val idx = tasks.indexOfFirst { key != null && it.id == key }.takeIf { it >= 0 }
            ?: tasks.indexOfLast { it.status.isActive }.takeIf { it >= 0 }
            ?: return tasks
        val t = tasks[idx]
        val done = t.copy(
            status = if (e.isError == true) TaskStatus.Failed else TaskStatus.Done,
            endedAtMs = nowMs,
            durationSec = e.duration ?: t.durationSec,
            detail = e.preview?.takeIf { it.isNotBlank() && it != t.title } ?: t.detail,
        )
        return tasks.toMutableList().also { it[idx] = done }
    }

    fun finishAll(tasks: List<ChatSubtask>, status: TaskStatus, nowMs: Long): List<ChatSubtask> =
        if (tasks.none { it.status.isActive }) tasks
        else tasks.map { if (it.status.isActive) it.copy(status = status, endedAtMs = nowMs) else it }

    /** Подзадачи из сохранённых вызовов инструментов (история) + живые (с временем), без повторов. */
    fun merged(live: List<ChatSubtask>, persisted: List<com.uzairansar.hermex.core.model.ToolCall>): List<ChatSubtask> {
        val out = live.toMutableList()
        persisted.filter { SubtaskPolicy.isSubtaskTool(it.displayName) || SubtaskPolicy.isSubtaskTool(it.function?.name) }
            .forEachIndexed { i, tool ->
                val title = SubtaskPolicy.title(tool.name ?: tool.function?.name, tool.args, tool.preview)
                if (out.none { (tool.id != null && it.id == tool.id) || it.title == title }) {
                    out += ChatSubtask(
                        id = tool.id ?: "hist-$i-$title",
                        title = title,
                        status = if (tool.isError == true) TaskStatus.Failed else TaskStatus.Done,
                        goals = SubtaskPolicy.goals(tool.args),
                        detail = toolResultText(tool),
                    )
                }
            }
        return out
    }

    fun toolResultText(tool: com.uzairansar.hermex.core.model.ToolCall): String? {
        val r = tool.result ?: return null
        val text = (r as? JsonPrimitive)?.contentOrNull ?: r.toString()
        return text.trim().takeIf { it.isNotEmpty() }?.let { if (it.length > 6000) it.take(6000) + "…" else it }
    }
}
