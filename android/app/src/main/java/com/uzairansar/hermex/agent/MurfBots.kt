package com.uzairansar.hermex.agent

import com.uzairansar.hermex.ui.chat.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ───────────────────────────── DTO ─────────────────────────────

@Serializable
data class BotDto(
    val id: String = "",
    val name: String = "",
    val emoji: String? = null,
    val instructions: String? = null,
    val workdir: String? = null,
    val backend: String = "hermes",
    val model: String? = null,
    val profile: String? = null,
    val access: String? = "safe",
    @SerialName("cli_confirmed") val cliConfirmed: JsonElement? = null,
    val chats: Int = 0,
    val running: Int = 0,
    @SerialName("last_active") val lastActive: Double? = null,
)

@Serializable
data class BotsResponse(val bots: List<BotDto> = emptyList())

@Serializable
data class CliDto(
    val id: String = "",
    val name: String = "",
    val installed: Boolean = false,
    val path: String = "",
    val version: String = "",
    @SerialName("logged_in") val loggedIn: Boolean? = null,
    @SerialName("login_method") val loginMethod: String = "",
    val backend: Boolean = false,
    val approvals: Boolean = false,
    val mode: String = "",
)

@Serializable
data class ClisResponse(
    val clis: List<CliDto> = emptyList(),
    val profiles: List<String> = listOf("default"),
    val workroot: String = "",
)

@Serializable
data class BotChatDto(
    val id: String = "",
    val bot: String = "",
    val title: String = "",
    val created: Double? = null,
    val updated: Double? = null,
    @SerialName("last_text") val lastText: String? = null,
    val running: Boolean = false,
    val parent: String? = null,
    val seq: Long = 0,
)

@Serializable
data class BotChatsResponse(val chats: List<BotChatDto> = emptyList())

@Serializable
data class BotChatDetail(
    val chat: BotChatDto = BotChatDto(),
    val bot: BotDto = BotDto(),
    val events: List<JsonObject> = emptyList(),
)

@Serializable
data class BotRunSubtask(
    val id: String = "",
    val title: String? = null,
    val status: String? = null,
    val started: Double? = null,
    val ended: Double? = null,
)

@Serializable
data class BotRunDto(
    val run: String = "",
    val bot: String = "",
    @SerialName("bot_name") val botName: String? = null,
    val emoji: String? = null,
    val chat: String = "",
    @SerialName("chat_title") val chatTitle: String? = null,
    val backend: String = "",
    val status: String = "",
    val started: Double? = null,
    val duration: Double? = null,
    val error: String? = null,
    @SerialName("from_bot") val fromBot: String? = null,
    val subtasks: List<BotRunSubtask> = emptyList(),
    @SerialName("subtasks_count") val subtasksCount: Int = 0,
)

@Serializable
data class BotRunsResponse(val runs: List<BotRunDto> = emptyList())

@Serializable
private data class BotApiError(
    val error: String? = null,
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    val notice: String? = null,
)

@Serializable
private data class BotSaveResponse(val bot: BotDto = BotDto())

@Serializable
private data class BotChatCreated(val chat: BotChatDto = BotChatDto())

class BotsException(message: String, val status: Int = 0) : Exception(message)

/** Сервер просит подтвердить включение CLI (аккаунт пользователя) или режима «как в конфиге CLI». */
class BotConfirmRequired(val notice: String) : Exception(notice)

/** Поля формы бота (то, что отправляется на сервер). */
data class BotDraft(
    val name: String = "",
    val emoji: String = "🤖",
    val instructions: String = "",
    val workdir: String = "",
    val backend: String = "hermes",
    val model: String = "",
    val profile: String = "default",
    val access: String = "safe",
) {
    fun toJson(confirm: Boolean): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("emoji", emoji.trim().ifEmpty { "🤖" })
        put("instructions", instructions)
        put("workdir", workdir.trim())
        put("backend", backend)
        put("model", model.trim())
        put("profile", profile)
        put("access", access)
        if (confirm) put("confirm_cli", true)
    }

    companion object {
        fun of(b: BotDto) = BotDraft(
            name = b.name, emoji = b.emoji ?: "🤖", instructions = b.instructions.orEmpty(), workdir = b.workdir.orEmpty(),
            backend = b.backend, model = b.model.orEmpty(), profile = b.profile ?: "default", access = b.access ?: "safe",
        )
    }
}

// ───────────────────────────── события и стенограмма ─────────────────────────────

/** Событие чата бота с сервера (seq растёт). */
data class BotEvent(val raw: JsonObject) {
    val seq: Long get() = raw.long("seq") ?: 0
    val ts: Double get() = raw.double("ts") ?: 0.0
    val type: String get() = raw.str("type").orEmpty()
    fun str(key: String) = raw.str(key)
    fun double(key: String) = raw.double(key)
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

data class BotOption(val id: String, val name: String, val kind: String?)
data class BotPlanEntry(val text: String, val status: String?)

sealed interface BotItem {
    val key: String

    data class User(override val key: String, val text: String, val fromBot: String?) : BotItem
    data class Assistant(override val key: String, val run: String?, val text: String) : BotItem
    data class Thinking(override val key: String, val text: String) : BotItem
    data class Step(
        override val key: String, val id: String, val title: String, val status: TaskStatus, val kind: String?,
        val detail: String?, val startedAtMs: Long?, val endedAtMs: Long?,
    ) : BotItem
    data class Subtask(
        override val key: String, val id: String, val title: String, val status: TaskStatus, val detail: String?,
        val startedAtMs: Long?, val endedAtMs: Long?, val children: List<BotItem>,
    ) : BotItem
    data class Approval(
        override val key: String, val id: String, val title: String, val detail: String?, val options: List<BotOption>,
        val pending: Boolean, val chosen: String?,
    ) : BotItem
    data class Plan(override val key: String, val entries: List<BotPlanEntry>) : BotItem
    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : BotItem
    data class Handoff(override val key: String, val outgoing: Boolean, val botName: String, val chatId: String?, val task: String) : BotItem
}

data class BotTranscript(val items: List<BotItem>, val running: Boolean, val lastSeq: Long, val pendingApprovals: Int)

/** Сворачивает журнал событий в элементы чата: текст склеивается, шаги/подзадачи обновляются по id. */
object BotTranscriptReducer {
    fun reduce(events: List<BotEvent>): BotTranscript {
        val top = mutableListOf<BotItem>()
        val subtaskChildren = linkedMapOf<String, MutableList<BotItem>>()
        val stepIndex = mutableMapOf<String, Pair<MutableList<BotItem>, Int>>()
        val approvals = mutableMapOf<String, Int>()
        var running = false
        var lastSeq = 0L

        fun target(parent: String?): MutableList<BotItem> =
            parent?.let { subtaskChildren[it] } ?: top

        fun appendText(list: MutableList<BotItem>, e: BotEvent, thinking: Boolean) {
            val t = e.str("text").orEmpty()
            if (t.isEmpty()) return
            val last = list.lastOrNull()
            if (thinking) {
                if (last is BotItem.Thinking) list[list.lastIndex] = last.copy(text = last.text + t)
                else list += BotItem.Thinking("th-${e.seq}", t)
            } else {
                val run = e.str("run")
                if (last is BotItem.Assistant && last.run == run) list[list.lastIndex] = last.copy(text = last.text + t)
                else list += BotItem.Assistant("as-${e.seq}", run, t)
            }
        }

        for (e in events.sortedBy { it.seq }) {
            lastSeq = maxOf(lastSeq, e.seq)
            when (e.type) {
                "user" -> top += BotItem.User("u-${e.seq}", e.str("text").orEmpty(), e.str("from_bot"))
                "text" -> appendText(target(e.str("parent")), e, thinking = false)
                "thinking" -> appendText(target(e.str("parent")), e, thinking = true)
                "step", "subtask" -> {
                    val id = e.str("id") ?: continue
                    val isSub = e.type == "subtask"
                    val status = TaskStatus.from(e.str("status"))
                    val started = e.double("started")?.let { (it * 1000).toLong() }
                    val ended = e.double("ended")?.let { (it * 1000).toLong() }
                    val existing = stepIndex[id]
                    if (existing != null) {
                        val (list, idx) = existing
                        list[idx] = when (val old = list[idx]) {
                            is BotItem.Step -> old.copy(title = e.str("title") ?: old.title, status = status,
                                detail = e.str("detail") ?: old.detail, endedAtMs = ended ?: old.endedAtMs)
                            is BotItem.Subtask -> old.copy(title = e.str("title") ?: old.title, status = status,
                                detail = e.str("detail") ?: old.detail, endedAtMs = ended ?: old.endedAtMs)
                            else -> old
                        }
                    } else {
                        val list = target(e.str("parent"))
                        val item = if (isSub) {
                            subtaskChildren[id] = mutableListOf()
                            BotItem.Subtask("sub-$id", id, e.str("title") ?: tr("Подзадача", "Subtask"), status,
                                e.str("detail"), started, ended, emptyList())
                        } else {
                            BotItem.Step("st-$id", id, e.str("title") ?: tr("шаг", "step"), status, e.str("kind"),
                                e.str("detail"), started, ended)
                        }
                        list += item
                        stepIndex[id] = list to list.lastIndex
                    }
                }
                "approval" -> {
                    val id = e.str("id") ?: continue
                    val pending = e.str("status") == "pending"
                    val idx = approvals[id]
                    if (idx == null) {
                        val opts = (e.raw["options"] as? JsonArray).orEmpty().mapNotNull { o ->
                            val obj = runCatching { o.jsonObject }.getOrNull() ?: return@mapNotNull null
                            val oid = obj.str("id") ?: return@mapNotNull null
                            BotOption(oid, obj.str("name") ?: oid, obj.str("kind"))
                        }
                        top += BotItem.Approval("ap-$id", id, e.str("title").orEmpty(), e.str("detail"), opts, pending, null)
                        approvals[id] = top.lastIndex
                    } else {
                        val old = top[idx] as BotItem.Approval
                        top[idx] = old.copy(pending = pending, chosen = e.str("chosen"))
                    }
                }
                "plan" -> {
                    val entries = (e.raw["entries"] as? JsonArray).orEmpty().mapNotNull { x ->
                        val o = runCatching { x.jsonObject }.getOrNull() ?: return@mapNotNull null
                        BotPlanEntry(o.str("text").orEmpty(), o.str("status"))
                    }
                    val i = top.indexOfLast { it is BotItem.Plan }
                    val lastUser = top.indexOfLast { it is BotItem.User }
                    if (i > lastUser && i >= 0) top[i] = BotItem.Plan(top[i].key, entries) else top += BotItem.Plan("pl-${e.seq}", entries)
                }
                "notice" -> top += BotItem.Notice("n-${e.seq}", e.str("text").orEmpty())
                "handoff" -> {
                    val outgoing = e.str("to_bot") != null
                    top += BotItem.Handoff("h-${e.seq}", outgoing, (if (outgoing) e.str("to_name") else e.str("from_name")).orEmpty(),
                        if (outgoing) e.str("to_chat") else e.str("from_chat"), e.str("task").orEmpty())
                }
                "run" -> {
                    val st = e.str("status")
                    running = st == "running"
                    if (st == "failed" || st == "stopped") {
                        val err = e.str("error")
                        top += BotItem.Notice("r-${e.seq}", if (st == "stopped") tr("Остановлено.", "Stopped.")
                        else tr("Ошибка: ", "Error: ") + (err ?: tr("запуск не удался", "run failed")), isError = st == "failed")
                    }
                }
            }
        }
        // дети подзадач — внутрь карточек
        fun attach(list: List<BotItem>): List<BotItem> = list.map { item ->
            if (item is BotItem.Subtask) item.copy(children = attach(subtaskChildren[item.id].orEmpty())) else item
        }
        val items = attach(top)
        val pending = items.count { it is BotItem.Approval && it.pending }
        return BotTranscript(items, running, lastSeq, pending)
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

// ───────────────────────────── API ─────────────────────────────

/** Клиент службы MURF bots: /phone/api/bots/… через relay (cookie WebUI + X-Agent-App). */
class MurfBotsApi(baseClient: OkHttpClient, cookieJar: CookieJar) {
    private val client = baseClient.newBuilder().cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val streamClient = client.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    private fun url(server: HttpUrl, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = server.newBuilder().encodedPath("/phone/api/bots$path").query(null)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun call(server: HttpUrl, method: String, path: String, body: JsonObject? = null, query: Map<String, String> = emptyMap()): String {
        val rb = Request.Builder().url(url(server, path, query)).header("X-Agent-App", "1")
        if (method == "POST") rb.post((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonType)) else rb.get()
        client.newCall(rb.build()).execute().use { r ->
            val text = r.body.string()
            if (r.isSuccessful) return text
            val err = runCatching { json.decodeFromString<BotApiError>(text) }.getOrNull()
            if (r.code == 409 && err?.confirmRequired == true) throw BotConfirmRequired(err.notice ?: err.error.orEmpty())
            throw BotsException(err?.error?.takeIf { it.isNotBlank() } ?: when (r.code) {
                404 -> tr("на сервере нет службы MURF bots (обновите сервер)", "the server has no MURF bots service (update the server)")
                401 -> tr("нужно войти заново", "please sign in again")
                503 -> tr("служба MURF bots не запущена", "MURF bots service is not running")
                else -> "HTTP ${r.code}"
            }, r.code)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: IOException) { throw BotsException(e.message ?: e.javaClass.simpleName) }
    }

    suspend fun bots(server: HttpUrl): List<BotDto> = io { json.decodeFromString<BotsResponse>(call(server, "GET", "/")).bots }
    suspend fun clis(server: HttpUrl, refresh: Boolean = false): ClisResponse =
        io { json.decodeFromString(call(server, "GET", "/clis", query = if (refresh) mapOf("refresh" to "1") else emptyMap())) }

    /** Создать/изменить бота; при необходимости подтверждения бросает [BotConfirmRequired]. */
    suspend fun save(server: HttpUrl, id: String?, draft: BotDraft, confirm: Boolean): BotDto = io {
        json.decodeFromString<BotSaveResponse>(call(server, "POST", if (id == null) "/" else "/$id", draft.toJson(confirm))).bot
    }

    suspend fun delete(server: HttpUrl, id: String) = io { call(server, "POST", "/$id/delete") }
    suspend fun chats(server: HttpUrl, botId: String): List<BotChatDto> =
        io { json.decodeFromString<BotChatsResponse>(call(server, "GET", "/$botId/chats")).chats }

    suspend fun newChat(server: HttpUrl, botId: String, text: String = ""): BotChatDto = io {
        json.decodeFromString<BotChatCreated>(call(server, "POST", "/$botId/chats", buildJsonObject { put("text", text) })).chat
    }

    suspend fun chat(server: HttpUrl, chatId: String, after: Long = 0): BotChatDetail =
        io { json.decodeFromString(call(server, "GET", "/chats/$chatId", query = mapOf("after" to after.toString()))) }

    suspend fun send(server: HttpUrl, chatId: String, text: String) =
        io { call(server, "POST", "/chats/$chatId/send", buildJsonObject { put("text", text) }) }

    suspend fun approve(server: HttpUrl, chatId: String, approvalId: String, option: String?) = io {
        call(server, "POST", "/chats/$chatId/approve", buildJsonObject {
            put("id", approvalId)
            if (option != null) put("option", option)
        })
    }

    suspend fun stop(server: HttpUrl, chatId: String) = io { call(server, "POST", "/chats/$chatId/stop") }
    suspend fun deleteChat(server: HttpUrl, chatId: String) = io { call(server, "POST", "/chats/$chatId/delete") }
    suspend fun handoff(server: HttpUrl, chatId: String, toBot: String, task: String): BotChatDto = io {
        json.decodeFromString<BotChatCreated>(call(server, "POST", "/chats/$chatId/handoff", buildJsonObject {
            put("to", toBot)
            put("task", task)
        })).chat
    }

    suspend fun runs(server: HttpUrl): List<BotRunDto> = io { json.decodeFromString<BotRunsResponse>(call(server, "GET", "/runs")).runs }

    /** Поток событий чата (SSE) начиная после [after]; завершается, когда сервер закрывает поток. */
    fun stream(server: HttpUrl, chatId: String, after: Long): Flow<BotEvent> = callbackFlow {
        val req = Request.Builder().url(url(server, "/chats/$chatId/stream", mapOf("after" to after.toString())))
            .header("X-Agent-App", "1").header("Accept", "text/event-stream").get().build()
        val call = streamClient.newCall(req)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw BotsException("HTTP ${r.code}", r.code)
                val src = r.body.source()
                val data = StringBuilder()
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    when {
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                        line.isEmpty() && data.isNotEmpty() -> {
                            runCatching { json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()?.let { trySend(BotEvent(it)) }
                            data.setLength(0)
                        }
                    }
                }
            }
            close()
        } catch (e: IOException) {
            close(BotsException(e.message ?: "stream closed"))
        } catch (e: BotsException) {
            close(e)
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    companion object {
        val backendNames = mapOf(
            "hermes" to "Hermes Agent", "codex" to "Codex CLI", "grok" to "Grok CLI", "claude" to "Claude Code",
            "gemini" to "Gemini CLI", "opencode" to "opencode",
        )
        val cliBackends = setOf("codex", "grok", "claude", "gemini", "opencode")

        fun accessLabel(access: String?): String = when (access) {
            "readonly" -> tr("только чтение", "read-only")
            "cli" -> tr("как в конфиге CLI", "as in CLI config")
            else -> tr("безопасный", "safe")
        }

        @Suppress("unused")
        private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    }
}
