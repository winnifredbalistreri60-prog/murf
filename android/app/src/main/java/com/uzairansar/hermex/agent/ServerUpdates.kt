package com.uzairansar.hermex.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Клиент серверных эндпоинтов обновления (relay на Hermes, за cookie WebUI). */
object ServerUpdatesApi {
    private fun http(jar: CookieJar) = OkHttpClient.Builder().cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    private fun url(server: HttpUrl, path: String) = server.newBuilder().encodedPath(path).build()

    fun status(server: HttpUrl, jar: CookieJar): JSONObject =
        http(jar).newCall(Request.Builder().url(url(server, "/phone/api/ops/agent-update")).get().build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }

    fun post(server: HttpUrl, jar: CookieJar, path: String, body: JSONObject = JSONObject()): JSONObject =
        http(jar).newCall(
            Request.Builder().url(url(server, path)).header("X-Agent-App", "1")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful && text.isBlank()) error("HTTP ${r.code}")
            JSONObject(text.ifBlank { "{}" })
        }

    fun log(server: HttpUrl, jar: CookieJar): String =
        http(jar).newCall(Request.Builder().url(url(server, "/phone/api/ops/agent-update/log")).get().build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JSONObject(r.body!!.string()).optString("log")
        }
}

/** Значок «есть обновление агента» для главного экрана; обновляется не чаще раза в 3 часа. */
object AgentUpdateBadge {
    private val _behind = MutableStateFlow(0)
    val behind: StateFlow<Int> = _behind
    private var lastCheck = 0L

    suspend fun refresh(server: HttpUrl, jar: CookieJar, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 3 * 3600_000L) return
        lastCheck = now
        runCatching {
            val s = withContext(Dispatchers.IO) { ServerUpdatesApi.status(server, jar) }
            set(s)
        }
    }

    fun set(status: JSONObject) {
        val chk = status.optJSONObject("check")
        _behind.value = if (chk != null && chk.optBoolean("update_available")) chk.optInt("behind", 1).coerceAtLeast(1) else 0
    }
}

private val Green = Color(0xFF2FB463)
private val Amber = Color(0xFFE0A100)
private val Red = Color(0xFFE5484D)

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ComponentRow(
    title: String,
    version: String,
    status: String,
    statusColor: Color,
    note: String?,
    checking: Boolean,
    busy: Boolean,
    updateEnabled: Boolean,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    tag: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        StatusChip(status, statusColor)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCheck, enabled = !checking && !busy) {
                if (checking) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Проверяю…", "Checking…"))
                } else {
                    Text(tr("Проверить", "Check"))
                }
            }
            Button(onClick = onUpdate, enabled = updateEnabled && !busy) { Text(tr("Обновить", "Update")) }
        }
    }
}

private fun stepIcon(status: String) = when (status) {
    "ok" -> "✓"
    "running" -> "⏳"
    "failed" -> "✗"
    "skipped" -> "—"
    else -> "•"
}

/**
 * Раздел «Обновления сервера» в настройках: Hermes WebUI и Hermes Agent.
 * Обновление агента выполняет серверный скрипт: бэкап → hermes update → рестарт → проверка → автооткат.
 */
@Composable
fun ServerUpdatesSection(
    server: HttpUrl,
    cookieJar: CookieJar,
    webUiGitUpdateBehind: Int?,
    onWebUiGitUpdate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmAgent by remember { mutableStateOf(false) }
    var confirmWebUi by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }
    var serverRestarting by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { withContext(Dispatchers.IO) { ServerUpdatesApi.status(server, cookieJar) } }
            .onSuccess { status = it; error = null; serverRestarting = false; AgentUpdateBadge.set(it) }
            .onFailure {
                val running = status?.optBoolean("running") == true
                if (running) serverRestarting = true else error = tr("Не удалось получить статус: ${it.message}", "Could not get status: ${it.message}")
            }
    }

    val checking = status?.optBoolean("checking") == true
    val running = status?.optBoolean("running") == true
    LaunchedEffect(server) { load() }
    LaunchedEffect(checking, running, serverRestarting) {
        while (checking || running || serverRestarting) {
            delay(3000)
            load()
        }
    }

    fun post(path: String, body: JSONObject = JSONObject()) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ServerUpdatesApi.post(server, cookieJar, path, body) } }
                .onSuccess { r ->
                    if (r.has("error") && r.optBoolean("ok", true).not()) error = r.optString("error")
                    if (r.has("check") || r.has("job")) status = r
                    delay(800)
                    load()
                }
                .onFailure { error = tr("Ошибка запроса: ${it.message}", "Request failed: ${it.message}") }
        }
    }

    val agentBehind = status?.optJSONObject("check")?.optInt("behind", 0) ?: 0
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("server_updates")) {
        Text(tr("Обновления сервера", "Server updates"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        // --- Hermes WebUI ---
        val w = status?.optJSONObject("webui")
        val wVer = w?.optString("version")?.takeIf { it.isNotBlank() && it != "null" }
        val wLatest = w?.optString("latest")?.takeIf { it.isNotBlank() && it != "null" }
        val wGitBehind = webUiGitUpdateBehind?.takeIf { it > 0 }
        val (wStatus, wColor) = when {
            status == null -> tr("проверяю…", "checking…") to MaterialTheme.colorScheme.secondary
            wGitBehind != null -> tr("доступно обновление · $wGitBehind", "update available · $wGitBehind") to Amber
            w?.optBoolean("update_available") == true -> tr("доступно обновление · $wLatest", "update available · $wLatest") to Amber
            wVer != null && wLatest != null -> tr("актуально", "up to date") to Green
            else -> tr("недоступно", "unavailable") to MaterialTheme.colorScheme.secondary
        }
        ComponentRow(
            title = "Hermes WebUI",
            version = listOfNotNull(wVer?.let { tr("версия $it", "version $it") }, wLatest?.let { tr("последний релиз $it", "latest release $it") }).joinToString(" · ").ifBlank { tr("версия неизвестна", "version unknown") },
            status = wStatus,
            statusColor = wColor,
            note = if (wGitBehind == null && w?.optBoolean("git") == false) tr("Установлен из архива — обновление WebUI делается вручную на сервере.", "Installed from an archive — update WebUI manually on the server.") else null,
            checking = false,
            busy = running,
            updateEnabled = wGitBehind != null,
            onCheck = { scope.launch { load() } },
            onUpdate = { confirmWebUi = true },
            tag = "update_row_webui",
        )

        // --- Hermes Agent ---
        val c = status?.optJSONObject("check")
        val behind = c?.optInt("behind", 0) ?: 0
        val available = c?.optBoolean("update_available") == true
        val pendingTail = c?.optBoolean("pending_tail") == true
        val (aStatus, aColor) = when {
            c == null && checking -> tr("проверяю…", "checking…") to MaterialTheme.colorScheme.secondary
            c == null -> tr("недоступно", "unavailable") to MaterialTheme.colorScheme.secondary
            c.optBoolean("ok").not() -> tr("недоступно: ", "unavailable: ") + c.optString("error").take(80) to Red
            available -> tr("доступно обновление · $behind ${plural(behind, "коммит", "коммита", "коммитов")}", "update available · $behind commit" + (if (behind == 1) "" else "s")) to Amber
            pendingTail -> tr("предыдущее обновление не завершено", "previous update not finished") to Amber
            else -> tr("актуально", "up to date") to Green
        }
        val aVersion = c?.let {
            listOfNotNull(
                it.optString("release").takeIf { r -> r.isNotBlank() && r != "null" }?.let { r -> "v$r" },
                it.optString("sha").takeIf { s -> s.isNotBlank() }?.let { s -> s.take(8) },
                it.optString("latest_sha").takeIf { s -> available && s.isNotBlank() }?.let { s -> "→ ${s.take(8)}" },
            ).joinToString(" · ")
        } ?: "—"
        val aNote = buildList {
            if (pendingTail) add(tr("Прошлое обновление не завершено (сбой установки npm-зависимостей). «Обновить» его доделает.", "The previous update did not finish (npm dependency install failed). “Update” will complete it."))
            c?.optString("checked")?.takeIf { it.isNotBlank() }?.let { add(tr("Проверено: ", "Checked: ") + "$it (Hermes) · " + tr("авто раз в ", "auto every ") + "${status?.optInt("check_every_hours", 3)} " + tr("ч", "h")) }
        }.joinToString("\n").ifBlank { null }
        ComponentRow(
            title = "Hermes Agent",
            version = aVersion,
            status = aStatus,
            statusColor = aColor,
            note = aNote,
            checking = checking,
            busy = running,
            updateEnabled = available || pendingTail,
            onCheck = { post("/phone/api/ops/agent-update/check") },
            onUpdate = { confirmAgent = true },
            tag = "update_row_agent",
        )

        // --- ход/итог последнего обновления ---
        val job = status?.optJSONObject("job")
        if (job != null) {
            val mode = if (job.optString("mode") == "dry-run") tr("Пробный запуск", "Dry run") else tr("Обновление агента", "Agent update")
            val jobOk = if (job.isNull("ok")) null else job.optBoolean("ok")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp)
                    .testTag("update_job"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "$mode · ${job.optString("started")}" + when {
                            running -> tr(" · выполняется", " · running")
                            jobOk == true -> tr(" · успешно", " · succeeded")
                            jobOk == false -> tr(" · ошибка", " · failed")
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val steps = job.optJSONArray("steps")
                for (i in 0 until (steps?.length() ?: 0)) {
                    val s = steps!!.getJSONObject(i)
                    Text(
                        "${stepIcon(s.optString("status"))} ${s.optString("title")}" +
                            s.optString("detail").takeIf { it.isNotBlank() }?.let { "\n   ${it.take(160)}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.optString("status") == "failed") Red else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (serverRestarting) Text(tr("Сервер перезапускается, жду ответа…", "Server is restarting, waiting for a response…"), style = MaterialTheme.typography.bodySmall, color = Amber)
                job.optString("message").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (jobOk == false) Red else Green)
                }
                if (job.optBoolean("rolled_back")) Text(tr("Выполнен откат на прежнюю версию.", "Rolled back to the previous version."), style = MaterialTheme.typography.bodySmall, color = Amber)
                Text(tr("Бэкап: ", "Backup: ") + job.optString("backup_dir"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = {
                    scope.launch {
                        logText = runCatching { withContext(Dispatchers.IO) { ServerUpdatesApi.log(server, cookieJar) } }
                            .getOrElse { tr("Журнал недоступен: ${it.message}", "Log unavailable: ${it.message}") }
                    }
                }) { Text(tr("Журнал", "Log")) }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Red) }
    }

    if (confirmAgent) {
        AlertDialog(
            onDismissRequest = { confirmAgent = false },
            title = { Text(tr("Обновить Hermes Agent?", "Update Hermes Agent?")) },
            text = {
                Text(
                    tr("Что будет сделано на сервере:\n", "What will happen on the server:\n") +
                        tr("1. Резервная копия: hermes-agent, настройки ~/.hermes, база state.db и локальные патчи (git stash) → ~/hermes-backups.\n", "1. Backup: hermes-agent, ~/.hermes settings, state.db and local patches (git stash) → ~/hermes-backups.\n") +
                        "2. hermes update (" + (if (agentBehind > 0) tr("$agentBehind ${plural(agentBehind, "коммит", "коммита", "коммитов")}", "$agentBehind commit" + (if (agentBehind == 1) "" else "s")) else tr("доделать прошлое обновление", "finish the previous update")) + ").\n" +
                        tr("3. Перезапуск шлюза и WebUI — Telegram-бот и чаты будут недоступны 1–3 минуты; сообщения, пришедшие в Telegram в это время, бот может пропустить.\n", "3. Restart of the gateway and WebUI — the Telegram bot and chats will be unavailable for 1–3 minutes; Telegram messages arriving meanwhile may be missed.\n") +
                        tr("4. Проверка: API, Telegram, WebUI. При ошибке — автоматический откат из копии.\n\n", "4. Check: API, Telegram, WebUI. On failure — automatic rollback from the backup.\n\n") +
                        tr("«Пробный запуск» сделает только копию и проверки, без обновления и перезапусков.", "“Dry run” only makes the backup and checks, without updating or restarting."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAgent = false
                    post("/phone/api/ops/agent-update/apply", JSONObject().put("confirm", "update"))
                }) { Text(tr("Обновить", "Update")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmAgent = false
                        post("/phone/api/ops/agent-update/apply", JSONObject().put("dry_run", true))
                    }) { Text(tr("Пробный запуск", "Dry run")) }
                    TextButton(onClick = { confirmAgent = false }) { Text(tr("Отмена", "Cancel")) }
                }
            },
            modifier = Modifier.testTag("agent_update_confirm"),
        )
    }
    if (confirmWebUi) {
        AlertDialog(
            onDismissRequest = { confirmWebUi = false },
            title = { Text(tr("Обновить Hermes WebUI?", "Update Hermes WebUI?")) },
            text = { Text(tr("WebUI скачает обновление и перезапустится. Открытые чаты ненадолго отключатся и переподключатся сами.", "WebUI will download the update and restart. Open chats will briefly disconnect and reconnect automatically.")) },
            confirmButton = { TextButton(onClick = { confirmWebUi = false; onWebUiGitUpdate() }) { Text(tr("Обновить", "Update")) } },
            dismissButton = { TextButton(onClick = { confirmWebUi = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    logText?.let { text ->
        AlertDialog(
            onDismissRequest = { logText = null },
            title = { Text(tr("Журнал обновления", "Update log")) },
            text = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text.takeLast(8000), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { logText = null }) { Text(tr("Закрыть", "Close")) } },
        )
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}
