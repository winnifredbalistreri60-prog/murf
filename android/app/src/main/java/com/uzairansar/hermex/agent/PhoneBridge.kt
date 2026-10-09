package com.uzairansar.hermex.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.uzairansar.hermex.HermexApplication
import com.uzairansar.hermex.MainActivity
import com.uzairansar.hermex.data.repository.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Инструменты, которые агент может вызвать на телефоне. */
enum class PhoneTool(val wire: String, private val titleRes: () -> String, val risky: Boolean) {
    RunShell("run_shell", { tr("Команды в Termux", "Termux commands") }, true),
    Location("get_location", { tr("Геопозиция", "Location") }, true),
    Photo("take_photo", { tr("Фото камерой", "Camera photo") }, true),
    ListFiles("list_files", { tr("Список файлов", "File list") }, false),
    PullFile("pull_file", { tr("Отдать файл с телефона", "Send file from phone") }, true),
    PushFile("push_file", { tr("Принять файл в Загрузки", "Receive file to Downloads") }, false),
    ClipGet("clipboard_get", { tr("Чтение буфера обмена", "Read clipboard") }, true),
    ClipSet("clipboard_set", { tr("Запись в буфер обмена", "Write clipboard") }, false),
    Notify("notify", { tr("Уведомления", "Notifications") }, false),
    Battery("battery", { tr("Заряд батареи", "Battery level") }, false),
    Sms("sms_list", { tr("Чтение SMS", "Read SMS") }, true);

    val title: String get() = titleRes()

    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } }
}

data class PhoneRequest(val id: String, val tool: String, val args: JsonObject, val raw: JsonObject) {
    fun arg(name: String): String? = args[name]?.jsonPrimitive?.contentOrNull
    val summary: String
        get() = when (tool) {
            "run_shell" -> "$ ${arg("command").orEmpty()}"
            "pull_file", "list_files" -> arg("path") ?: "~/storage/shared"
            "push_file" -> arg("name").orEmpty()
            "clipboard_set" -> arg("text").orEmpty().take(80)
            "notify" -> listOfNotNull(arg("title"), arg("text")).joinToString(": ")
            "take_photo" -> tr("камера: ", "camera: ") + (arg("camera") ?: "back")
            else -> ""
        }
}

data class PhoneLogEntry(val time: Long, val tool: String, val summary: String, val state: String)

data class PhoneBridgeUi(
    val running: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null,
    val pending: List<PhoneRequest> = emptyList(),
    val log: List<PhoneLogEntry> = emptyList(),
)

object PhoneBridgeSettings {
    private const val PREFS = "agent_phone_bridge"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    fun deviceId(c: Context): String = p(c).getString("device", null) ?: UUID.randomUUID().toString().take(12).also {
        p(c).edit().putString("device", it).apply()
    }
    fun autoAllowed(c: Context): Set<String> = p(c).getStringSet("auto", setOf("battery", "notify", "push_file"))!!.toSet()
    fun setAutoAllowed(c: Context, tool: String, v: Boolean) {
        val s = autoAllowed(c).toMutableSet(); if (v) s.add(tool) else s.remove(tool)
        p(c).edit().putStringSet("auto", s).apply()
    }
    fun smsEnabled(c: Context) = p(c).getBoolean("sms", false)
    fun setSmsEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("sms", v).apply()
}

object PhoneBridge {
    val state = MutableStateFlow(PhoneBridgeUi())
    val ui: StateFlow<PhoneBridgeUi> get() = state
    internal val decisions = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun start(context: Context) {
        PhoneBridgeSettings.setEnabled(context, true)
        val i = Intent(context, PhoneBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    fun stop(context: Context) {
        PhoneBridgeSettings.setEnabled(context, false)
        context.stopService(Intent(context, PhoneBridgeService::class.java))
        state.update { it.copy(running = false, connected = false) }
    }

    /** decision: allow | deny | always */
    fun decide(context: Context, requestId: String, decision: String) {
        decisions[requestId]?.complete(decision)
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
    }

    internal fun log(tool: String, summary: String, st: String) = state.update {
        it.copy(log = (listOf(PhoneLogEntry(System.currentTimeMillis(), tool, summary, st)) + it.log).take(40))
    }
}

class PhoneDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneBridge.decide(context, intent.getStringExtra("id") ?: return, intent.getStringExtra("decision") ?: "deny")
    }
}

class PhoneBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, CH_BRIDGE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(tr("MURF: мост «Телефон» включён", "MURF: phone bridge is on"))
            .setContentText(tr("Агент может запрашивать действия на телефоне (с подтверждением)", "The agent can request actions on the phone (with confirmation)"))
            .setOngoing(true)
            .setContentIntent(openAppIntent(this))
            .build()
        ServiceCompat.startForeground(this, 4401, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        PhoneBridge.state.update { it.copy(running = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        PhoneBridge.state.update { it.copy(running = false, connected = false) }
        super.onDestroy()
    }

    private fun server(): HttpUrl? {
        val app = application as HermexApplication
        return (app.container.authRepository.state.value as? AuthState.LoggedIn)?.server
    }

    private fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar((application as HermexApplication).container.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    private suspend fun runLoop() {
        val http = client()
        var backoff = 2_000L
        var helloDone = false
        while (scope.isActive) {
            val base = server()
            if (base == null) {
                PhoneBridge.state.update { it.copy(connected = false, lastError = tr("Не выполнен вход в приложение", "Not logged in to the app")) }
                delay(10_000); continue
            }
            try {
                val device = PhoneBridgeSettings.deviceId(this)
                if (!helloDone) {
                    val body = buildJsonObject {
                        put("device", device)
                        put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("termux", Termux.isInstalled(this@PhoneBridgeService) && Termux.hasPermission(this@PhoneBridgeService))
                        put("app_version", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
                        putJsonArray("caps") { PhoneTool.entries.forEach { add(JsonPrimitive(it.wire)) } }
                    }
                    post(http, base, "phone/api/hello", body.toString())
                    helloDone = true
                }
                val url = base.newBuilder().addPathSegments("phone/api/poll")
                    .addQueryParameter("device", device).addQueryParameter("wait", "25").build()
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 -> {
                            PhoneBridge.state.update { it.copy(connected = false, lastError = tr("Сессия истекла — войдите в приложение заново", "Session expired — log in again")) }
                            delay(15_000)
                        }
                        resp.code == 204 -> PhoneBridge.state.update { it.copy(connected = true, lastError = null) }
                        resp.isSuccessful -> {
                            PhoneBridge.state.update { it.copy(connected = true, lastError = null) }
                            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject["request"]!!.jsonObject
                            val req = PhoneRequest(
                                id = obj["id"]!!.jsonPrimitive.content,
                                tool = obj["tool"]!!.jsonPrimitive.content,
                                args = (obj["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                                raw = obj,
                            )
                            scope.launch { handle(http, base, req) }
                        }
                        else -> {
                            PhoneBridge.state.update { it.copy(connected = false, lastError = tr("Сервер ответил ${resp.code}", "Server responded ${resp.code}")) }
                            delay(backoff)
                        }
                    }
                }
                backoff = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                helloDone = false
                PhoneBridge.state.update { it.copy(connected = false, lastError = e.message ?: e.javaClass.simpleName) }
                delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun post(http: OkHttpClient, base: HttpUrl, path: String, body: String) {
        val url = base.newBuilder().addPathSegments(path).build()
        http.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build())
            .execute().close()
    }

    private suspend fun handle(http: OkHttpClient, base: HttpUrl, req: PhoneRequest) {
        val tool = PhoneTool.of(req.tool)
        val timeoutSec = req.raw["timeout"]?.jsonPrimitive?.intOrNull ?: 120
        val result: JsonObject = if (tool == null) {
            fail(tr("Неизвестный инструмент ${req.tool}", "Unknown tool ${req.tool}"))
        } else if (tool == PhoneTool.Sms && !PhoneBridgeSettings.smsEnabled(this)) {
            fail(tr("Чтение SMS выключено в приложении MURF (Телефон → SMS)", "SMS reading is disabled in MURF (Phone → SMS)"))
        } else {
            val allowed = if (req.tool in PhoneBridgeSettings.autoAllowed(this)) "allow" else askUser(req, tool, timeoutSec)
            when (allowed) {
                "always" -> { PhoneBridgeSettings.setAutoAllowed(this, req.tool, true); execute(http, req, tool) }
                "allow" -> execute(http, req, tool)
                "timeout" -> fail(tr("Пользователь не подтвердил запрос вовремя", "The user did not confirm the request in time"))
                else -> fail(tr("Пользователь отклонил запрос", "The user declined the request"))
            }
        }
        PhoneBridge.log(req.tool, req.summary, if (result["ok"]?.jsonPrimitive?.content == "true") tr("выполнено", "done") else tr("ошибка/отказ", "error/declined"))
        runCatching {
            val body = buildJsonObject { put("id", req.id); result.forEach { (k, v) -> put(k, v) } }
            post(http, base, "phone/api/result", body.toString())
        }
    }

    private suspend fun askUser(req: PhoneRequest, tool: PhoneTool, timeoutSec: Int): String {
        val d = CompletableDeferred<String>()
        PhoneBridge.decisions[req.id] = d
        PhoneBridge.state.update { it.copy(pending = it.pending + req) }
        fun action(decision: String, code: Int) = PendingIntent.getBroadcast(
            this, req.id.hashCode() * 4 + code,
            Intent(this, PhoneDecisionReceiver::class.java).putExtra("id", req.id).putExtra("decision", decision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_CONFIRM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(tr("Агент просит: ${tool.title}", "Agent requests: ${tool.title}"))
            .setContentText(req.summary.ifBlank { tool.title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.summary.ifBlank { tool.title }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent(this, "phone"))
            .addAction(0, tr("Разрешить", "Allow"), action("allow", 1))
            .addAction(0, tr("Отклонить", "Deny"), action("deny", 2))
            .addAction(0, tr("Всегда", "Always"), action("always", 3))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(req.id.hashCode(), n) }
        val decision = withTimeoutOrNull((timeoutSec - 5).coerceAtLeast(10) * 1000L) { d.await() } ?: "timeout"
        PhoneBridge.decisions.remove(req.id)
        PhoneBridge.state.update { s -> s.copy(pending = s.pending.filterNot { it.id == req.id }) }
        NotificationManagerCompat.from(this).cancel(req.id.hashCode())
        return decision
    }

    private fun ok(output: String, extra: Map<String, String> = emptyMap()) = buildJsonObject {
        put("ok", true); put("output", output.take(100_000)); extra.forEach { (k, v) -> put(k, v) }
    }

    private fun fail(error: String, output: String = "") = buildJsonObject {
        put("ok", false); put("error", error); if (output.isNotBlank()) put("output", output.take(50_000))
    }

    private fun fromTermux(r: TermuxResult) = buildJsonObject {
        put("ok", r.ok); put("output", r.combined().take(100_000)); r.exitCode?.let { put("exit_code", it) }
        r.error?.let { put("error", it) }
    }

    private suspend fun execute(http: OkHttpClient, req: PhoneRequest, tool: PhoneTool): JsonObject {
        val q = Termux::shellQuote
        val upload = req.raw["upload_url"]?.jsonPrimitive?.contentOrNull
        return when (tool) {
            PhoneTool.RunShell -> fromTermux(Termux.run(this, req.arg("command").orEmpty(), req.arg("workdir"),
                ((req.raw["timeout"]?.jsonPrimitive?.intOrNull ?: 120) - 10).coerceAtLeast(10) * 1000L))
            PhoneTool.Location -> fromTermux(Termux.run(this,
                "termux-location -p network -r once 2>/dev/null | grep -q latitude && termux-location -p network -r once || termux-location -p gps -r once", timeoutMs = 90_000))
            PhoneTool.Photo -> {
                val cam = if (req.arg("camera") == "front") 1 else 0
                fromTermux(Termux.run(this, "F=\$HOME/.agent_photo.jpg; rm -f \"\$F\"; termux-camera-photo -c $cam \"\$F\" && " +
                    "curl -sS --fail --data-binary @\"\$F\" ${q("$upload?name=photo-${AgentShare.stamp()}.jpg")}", timeoutMs = 100_000))
            }
            PhoneTool.ListFiles -> fromTermux(Termux.run(this,
                "ls -la --group-directories-first ${req.arg("path")?.let(q) ?: "\$HOME/storage/shared/"}", timeoutMs = 30_000))
            PhoneTool.PullFile -> {
                val path = req.arg("path").orEmpty()
                val name = AgentShare.safeFileName(path.substringAfterLast('/'), "file")
                fromTermux(Termux.run(this, "curl -sS --fail --data-binary @${q(path)} ${q("$upload?name=" + java.net.URLEncoder.encode(name, "UTF-8"))}", timeoutMs = 280_000))
            }
            PhoneTool.PushFile -> {
                val url = req.raw["download_url"]?.jsonPrimitive?.contentOrNull ?: return fail(tr("нет download_url", "no download_url"))
                val bytes = http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (!r.isSuccessful) return fail(tr("скачивание: HTTP ${r.code}", "download: HTTP ${r.code}")); r.body!!.bytes()
                }
                val name = req.arg("name") ?: "file"
                ok(tr("Сохранено: ", "Saved: ") + AgentShare.saveToDownloads(this, name, "application/octet-stream", bytes))
            }
            PhoneTool.ClipGet -> fromTermux(Termux.run(this, "termux-clipboard-get", timeoutMs = 30_000))
            PhoneTool.ClipSet -> {
                val text = req.arg("text").orEmpty()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MURF", text))
                }
                ok(tr("Скопировано ${text.length} символов", "Copied ${text.length} characters"))
            }
            PhoneTool.Notify -> {
                val n = NotificationCompat.Builder(this, CH_AGENT)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(req.arg("title") ?: "MURF")
                    .setContentText(req.arg("text").orEmpty())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(req.arg("text").orEmpty()))
                    .setContentIntent(openAppIntent(this)).setAutoCancel(true).build()
                runCatching { NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % 100000).toInt() + 5000, n) }
                    .fold({ ok(tr("Уведомление показано", "Notification shown")) }, { fail(tr("Нет разрешения на уведомления", "No notification permission")) })
            }
            PhoneTool.Battery -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                ok(tr("Заряд: ", "Battery: ") + "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, " + tr("заряжается: ", "charging: ") + (if (bm.isCharging) tr("да", "yes") else tr("нет", "no")))
            }
            PhoneTool.Sms -> {
                val limit = req.args["limit"]?.jsonPrimitive?.intOrNull ?: 10
                fromTermux(Termux.run(this, "termux-sms-list -l ${limit.coerceIn(1, 100)}", timeoutMs = 60_000))
            }
        }
    }

    companion object {
        const val CH_BRIDGE = "phone_bridge"
        const val CH_CONFIRM = "phone_confirm"
        const val CH_AGENT = "agent_messages"

        fun ensureChannels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_BRIDGE, tr("Мост «Телефон»", "Phone bridge"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_CONFIRM, tr("Запросы MURF к телефону", "MURF requests to the phone"), NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_AGENT, tr("Сообщения MURF", "MURF messages"), NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun openAppIntent(c: Context, route: String? = null): PendingIntent {
            val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) i.putExtra("agent_route", route)
            return PendingIntent.getActivity(c, route.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

@Suppress("unused")
private fun Notification.noop() = Unit
