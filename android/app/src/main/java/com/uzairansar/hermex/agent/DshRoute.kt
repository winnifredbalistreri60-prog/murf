package com.uzairansar.hermex.agent

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.uzairansar.hermex.data.repository.AuthRepository
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Элемент ленты чата DeepSeek Harness. */
sealed class DshItem {
    data class User(val text: String) : DshItem()
    data class Assistant(val text: String) : DshItem()
    data class Thinking(val text: String) : DshItem()
    data class Tool(val callId: String, val name: String, val input: String, val result: String? = null, val status: String? = null) : DshItem() {
        val phone get() = name.startsWith("mcp__murf_phone__")
        val shortName get() = name.removePrefix("mcp__murf_phone__")
    }
    data class Usage(val turn: TurnStats) : DshItem()
    data class Error(val text: String) : DshItem()
}

/** Свёртка событий в ленту (общая для живого потока и повтора из журнала). */
class DshTranscript {
    val items = mutableStateListOf<DshItem>()
    val usage = DshUsageAccumulator()
    var sessionId: String? = null
    var model: String = ""

    fun add(e: JSONObject) {
        usage.add(e)
        when (e.optString("type")) {
            "murf_run" -> { model = e.optString("model"); items += DshItem.User(e.optString("prompt")) }
            "session" -> sessionId = e.optString("sessionId").ifBlank { sessionId }
            "text" -> appendText(e.optString("text"), thinking = false)
            "thinking" -> appendText(e.optString("text"), thinking = true)
            "tool_call" -> items += DshItem.Tool(e.optString("callId"), e.optString("tool"), e.opt("input")?.toString().orEmpty())
            "tool_result" -> {
                val i = items.indexOfLast { it is DshItem.Tool && it.callId == e.optString("callId") }
                if (i >= 0) items[i] = (items[i] as DshItem.Tool).copy(result = e.optString("result"), status = e.optString("status"))
            }
            "status" -> if (e.optString("phase") == "turn_end") usage.turns.lastOrNull()?.let { items += DshItem.Usage(it) }
            "error" -> items += DshItem.Error(e.optString("error"))
            "murf_exit" -> if (e.optInt("code") != 0) items += DshItem.Error(tr("dsh завершился с кодом ", "dsh exited with code ") + e.optInt("code") +
                e.optString("stderr").takeIf { it.isNotBlank() }?.let { "\n" + it.takeLast(800) }.orEmpty())
        }
    }

    private fun appendText(t: String, thinking: Boolean) {
        val last = items.lastOrNull()
        if (!thinking && last is DshItem.Assistant) items[items.lastIndex] = DshItem.Assistant(last.text + t)
        else if (thinking && last is DshItem.Thinking) items[items.lastIndex] = DshItem.Thinking(last.text + t)
        else items += if (thinking) DshItem.Thinking(t) else DshItem.Assistant(t)
    }
}

object DshLocal {
    private const val BASE = LocalEnv.URL + "/phone/api/dsh"
    private fun http(jar: CookieJar, read: Long = 30) = OkHttpClient.Builder().cookieJar(jar)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(read, TimeUnit.SECONDS).build()
    private fun rq(path: String) = Request.Builder().url("$BASE/$path").header("X-Agent-App", "1")

    suspend fun get(jar: CookieJar, path: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { http(jar).newCall(rq(path).get().build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null } }.getOrNull()
    }
    suspend fun post(jar: CookieJar, path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            http(jar).newCall(rq(path).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
                .use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else JSONObject().put("error", "HTTP ${r.code}") }
        }.getOrNull()
    }

    /** Запуск хода: события построчно в onEvent. Ключи провайдеров — только в теле запроса к 127.0.0.1. */
    suspend fun run(context: Context, jar: CookieJar, prompt: String, sessionId: String?, phoneTools: Boolean, onEvent: (JSONObject) -> Unit) =
        withContext(Dispatchers.IO) {
            val sel = MurfProviders.selected(context)
            if (sel == null) {
                withContext(Dispatchers.Main) { onEvent(JSONObject().put("type", "error").put("error", tr("Выберите провайдера и модель", "Choose a provider and model"))) }
                return@withContext
            }
            val (prov, model) = sel
            val body = JSONObject().put("prompt", prompt).put("provider", prov).put("model", model)
                .put("providers", MurfProviders.forLocalRun(context)).put("phone_tools", phoneTools)
            if (sessionId != null) body.put("session_id", sessionId)
            try {
                http(jar, read = 0).newCall(rq("run").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        val code = r.code
                        withContext(Dispatchers.Main) { onEvent(JSONObject().put("type", "error").put("error", if (r.code == 401 || r.code == 403)
                            tr("Нет входа в локальную среду (войдите в «Телефон (локально)» один раз)", "Not logged in to the local environment") else "HTTP $code")) }
                        return@use
                    }
                    val src = r.body!!.source()
                    while (isActive) {
                        val line = src.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        withContext(Dispatchers.Main) { onEvent(ev) }
                        if (ev.optString("type") == "murf_exit") break
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onEvent(JSONObject().put("type", "error").put("error", e.message ?: e.javaClass.simpleName)) }
            }
        }
}


/**
 * Last successful DeepSeek Harness remote base URL (user's own server after pairing).
 * Never ships a hardcoded owner IP — empty until this device pairs once.
 */
object DshRemotePrefs {
    private const val PREFS = "murf_dsh_remote"
    private const val KEY_BASE = "last_base"
    private var sp: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (sp != null) return
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun lastBase(): String? =
        sp?.getString(KEY_BASE, null)?.trim()?.takeIf { it.isNotBlank() }

    fun saveBase(url: String) {
        val normalized = url.trim().trimEnd('/') + "/"
        if (normalized == "https://" || normalized == "http://") return
        sp?.edit()?.putString(KEY_BASE, normalized)?.apply()
    }
}

fun findDshRemoteBase(cookieJar: CookieJar, serverUrls: List<String>): String? {
    for (raw in serverUrls) {
        val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: continue
        if (cookieJar.loadForRequest(url).any { it.name == "dsh_remote_session" && it.value.isNotBlank() }) {
            return raw.trimEnd('/') + "/"
        }
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshRoute(
    cookieJar: CookieJar,
    auth: AuthRepository,
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onDevices: () -> Unit,
    onLocal: () -> Unit = {},
    onPhone: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) } // 0 phone, 1 Hermes remote
    var openChat by remember { mutableStateOf<String?>(null) }   // "" — новый чат
    var refresh by remember { mutableIntStateOf(0) }

    openChat?.let { sid ->
        DshChat(cookieJar, sid.ifBlank { null }, onBack = { openChat = null; refresh++ }, onProviders = onProviders)
        return
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Harness", "Harness")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
            actions = {
                if (tab == 0) IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Update")) }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("На телефоне", "On phone")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("На Hermes", "On Hermes")) })
            }
            when (tab) {
                1 -> DshRemotePanel(cookieJar = cookieJar, auth = auth, onDevices = onDevices, onLocal = onLocal, onPhone = onPhone, onProviders = onProviders)
                else -> DshPhonePanel(cookieJar = cookieJar, refresh = refresh, onRefresh = { refresh++ }, onOpenChat = { openChat = it }, onProviders = onProviders)
            }
        }
    }
}

@Composable
private fun DshPhonePanel(
    cookieJar: CookieJar,
    refresh: Int,
    onRefresh: () -> Unit,
    onOpenChat: (String) -> Unit,
    onProviders: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var sessions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var health by remember { mutableStateOf<JSONObject?>(null) }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onRefresh() }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(refresh) {
        while (isActive) {
            health = LocalEnv.health()
            status = DshLocal.get(cookieJar, "status")
            DshLocal.get(cookieJar, "sessions")?.optJSONArray("sessions")?.let { a -> sessions = List(a.length()) { a.getJSONObject(it) } }
            delay(if (status?.optJSONObject("progress")?.optString("state") == "running") 3000 else 20000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val st = status
                        when {
                            health == null -> Text(tr("Локальная среда не запущена. Запустите её в «Локальная среда».", "The local environment is not running. Start it in “Local environment”."))
                            st == null -> Text(tr("Нет входа в локальную среду — войдите в «Телефон (локально)» хотя бы раз.", "Not logged in to the local environment — log in to “Phone (local)” once."))
                            st.optBoolean("installed") -> Text(tr("Установлен dsh ", "Installed dsh ") + st.optString("version") +
                                tr(" · мост телефона: ", " · phone bridge: ") + (if (st.optBoolean("phone_bridge")) tr("подключён", "connected") else tr("не подключён", "not connected")))
                            else -> {
                                val pr = st.optJSONObject("progress")
                                Text(tr("DeepSeek Harness ещё не установлен в Ubuntu на телефоне (~350 МБ: Node.js 22 + dsh 0.2.1-rc.2).",
                                    "DeepSeek Harness is not installed yet (~350 MB: Node.js 22 + dsh 0.2.1-rc.2)."))
                                if (pr != null) {
                                    Text(pr.optString("msg"), style = MaterialTheme.typography.bodySmall)
                                    if (pr.optString("state") == "running") LinearProgressIndicator(progress = { pr.optInt("pct") / 100f }, Modifier.fillMaxWidth())
                                }
                                if (pr?.optString("state") != "running") Button(onClick = { scope.launch { DshLocal.post(cookieJar, "install", JSONObject()); onRefresh() } }) {
                                    Text(if (pr?.optString("state") == "error") tr("Повторить установку", "Retry install") else tr("Установить", "Install"))
                                }
                            }
                        }
                        val sel = remember(refresh, status) { MurfProviders.selected(context) }
                        Text(tr("Модель: ", "Model: ") + (sel?.let { "${it.first} / ${it.second}" } ?: tr("не выбрана", "not chosen")), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onProviders) { Text(tr("Провайдеры и ключи", "Providers & keys")) }
                            Button(onClick = { onOpenChat("") }, enabled = st?.optBoolean("installed") == true) { Text(tr("Новый чат", "New chat")) }
                        }
                        Text(tr("DeepSeek Harness — открытый агент DeepSeek (MIT). Работает в Ubuntu (proot) на телефоне, ключи хранятся в MURF зашифрованными. Телеметрия dsh отключена.",
                            "DeepSeek Harness is DeepSeek's open agent (MIT). It runs in Ubuntu (proot) on the phone; keys are stored encrypted in MURF. dsh telemetry is off."),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (sessions.isNotEmpty()) item { Text(tr("Сессии", "Sessions"), style = MaterialTheme.typography.titleMedium) }
            items(sessions, key = { it.optString("id") }) { s ->
                val u = s.optJSONObject("usage")
                Card(Modifier.fillMaxWidth().clickable { onOpenChat(s.optString("id")) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.optString("title").ifBlank { s.optString("id") }, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text(SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(s.optLong("updated") * 1000)) + " · " +
                            s.optString("route") + " · " + tr("ходов ", "turns ") + s.optInt("turns") +
                            (u?.let { " · " + fmtTokens(it.optLong("input") + it.optLong("cacheRead") + it.optLong("cacheWrite") + it.optLong("output")) + " tok" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshRemotePanel(
    cookieJar: CookieJar,
    auth: AuthRepository,
    onDevices: () -> Unit,
    onLocal: () -> Unit,
    onPhone: () -> Unit,
    onProviders: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by auth.servers.collectAsState()
    var remembered by remember { mutableStateOf(DshRemotePrefs.lastBase()) }
    var refresh by remember { mutableIntStateOf(0) }
    val candidateUrls = remember(servers, remembered, refresh) {
        buildList {
            remembered?.let { add(it) }
            servers.servers.forEach { add(it.urlString) }
        }.distinct()
    }
    val paired = remember(candidateUrls, cookieJar, refresh) {
        findDshRemoteBase(cookieJar, candidateUrls)
    }
    val openTarget = paired ?: remembered

    var server by remember { mutableStateOf(remembered ?: "https://") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val canSubmit = remember(server, code) {
        val raw = if (server.contains("auth=", ignoreCase = true)) server else "$server $code"
        val p = PairPayloadParser.parse(raw)
        p != null && p.protocol == PairProtocol.DshRemote
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trimEnd('/') + "/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun submitPair() {
        val raw = if (server.contains("auth=", ignoreCase = true)) server.trim() else "${server.trim()} ${code.trim()}"
        val payload = PairPayloadParser.parse(raw)
        if (payload == null || payload.protocol != PairProtocol.DshRemote) {
            error = tr("Нужна ссылка Harness с ?auth= или адрес + 8‑символьный код", "Need a Harness ?auth= link or address + 8-character code")
            return
        }
        busy = true
        error = null
        info = null
        scope.launch {
            runCatching { auth.pairWithDshAuth(payload.server.toString(), payload.code) }
                .onSuccess {
                    DshRemotePrefs.saveBase(payload.server.toString())
                    remembered = DshRemotePrefs.lastBase()
                    server = remembered ?: payload.server.toString()
                    code = ""
                    info = tr(
                        "Harness привязан. Дальше можно открывать без компа — cookie на телефоне. Для постоянного входа один раз сохраните «постоянный доступ» в Harness «Удалённый доступ».",
                        "Harness paired. You can reopen from the phone — the cookie stays here. For lasting access, save the permanent code once from Harness Remote access.",
                    )
                    refresh++
                }
                .onFailure {
                    error = it.message ?: it.javaClass.simpleName
                }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Harness на Hermes", "Harness on Hermes"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr(
                            "Удалённый DeepSeek Harness. Вставьте свою ссылку или адрес + код с экрана «Удалённый доступ» (временный 5 мин или постоянный). Чужой сервер по умолчанию не подставляется.",
                            "Remote DeepSeek Harness. Paste your own link or address + code from Remote access (5‑min temporary or permanent). No third-party server is prefilled.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (openTarget != null && paired != null) {
                        Text(tr("Сессия есть: ", "Session ready: ") + paired, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { openUrl(paired) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Открыть Harness", "Open Harness"))
                        }
                    } else if (remembered != null) {
                        Text(tr("Запомнен адрес: ", "Remembered address: ") + remembered, style = MaterialTheme.typography.bodySmall)
                        Text(
                            tr("Сессии ещё нет — введите код ещё раз (лучше постоянный).", "No session yet — enter the code again (prefer the permanent one)."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(tr("Адрес или ссылка ?auth=", "Address or ?auth= link")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.trim().uppercase() },
                        label = { Text(tr("Код (8 символов, если не в ссылке)", "Code (8 chars if not in the link)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    Text(
                        tr(
                            "Один раз сохраните постоянный код в Harness «Удалённый доступ» — дальше без экрана на компе.",
                            "Save the permanent code once in Harness Remote access — then no desktop screen needed.",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Button(
                        onClick = { submitPair() },
                        enabled = canSubmit && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) tr("Подключение…", "Connecting…") else tr("Подключить Harness", "Connect Harness"))
                    }
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (info != null) Text(info!!, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDevices, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Устройства (Hermes QR)", "Devices (Hermes QR)"))
                    }
                }
            }
        }
        item {
            Text(tr("Связанные экраны", "Related screens"), style = MaterialTheme.typography.titleSmall)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocal, modifier = Modifier.weight(1f)) { Text(tr("Локальная среда", "Local environment")) }
                OutlinedButton(onClick = onPhone, modifier = Modifier.weight(1f)) { Text(tr("Телефон и Termux", "Phone & Termux")) }
            }
        }
        item {
            OutlinedButton(onClick = onProviders, modifier = Modifier.fillMaxWidth()) { Text(tr("Провайдеры моделей", "Model providers")) }
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshChat(cookieJar: CookieJar, initialSession: String?, onBack: () -> Unit, onProviders: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tr0 = remember { DshTranscript().also { it.sessionId = initialSession } }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var phoneTools by remember { mutableStateOf(true) }
    var userControl by remember { mutableStateOf(MurfAccessibilityService.userHasControl) }
    var statsVer by remember { mutableIntStateOf(0) }
    val bridge by PhoneBridge.ui.collectAsState()
    val list = rememberLazyListState()
    var busy by remember { mutableStateOf(false) }
    val running = busy
    androidx.activity.compose.BackHandler(onBack = onBack)

    LaunchedEffect(initialSession) {
        if (initialSession != null) DshLocal.get(cookieJar, "session?id=$initialSession")?.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) tr0.add(a.getJSONObject(i)); statsVer++
        }
    }
    LaunchedEffect(tr0.items.size) { if (tr0.items.isNotEmpty()) list.animateScrollToItem(tr0.items.size - 1) }

    fun send(text: String) {
        if (text.isBlank() || running) return
        if (phoneTools && !PhoneBridgeSettings.enabled(context)) PhoneBridge.start(context)
        busy = true
        job = scope.launch {
            try {
                DshLocal.run(context, cookieJar, text, tr0.sessionId, phoneTools) { tr0.add(it); statsVer++ }
            } finally {
                busy = false
                statsVer++
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initialSession == null && tr0.sessionId == null) tr("Новый чат DSH", "New DSH chat") else tr("Чат DSH", "DSH chat"), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            key(statsVer) { if (tr0.usage.turns.isNotEmpty()) SessionStatsBar(tr0.usage.session(), tr0.model) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tr0.items) { DshItemView(it, onRetry = { p -> send(p) }) }
                bridge.pending.forEach { req ->
                    item(key = "pend-" + req.id) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val t = PhoneTool.of(req.tool)
                                Text(tr("Агент просит: ", "Agent asks: ") + (t?.title ?: req.tool), fontWeight = FontWeight.SemiBold)
                                if (req.summary.isNotBlank()) Text(req.summary, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { PhoneBridge.decide(context, req.id, "allow") }) { Text(tr("Разрешить", "Allow")) }
                                    OutlinedButton(onClick = { PhoneBridge.decide(context, req.id, "deny") }) { Text(tr("Отклонить", "Deny")) }
                                    if (t != null && !t.screenControl) TextButton(onClick = { PhoneBridge.decide(context, req.id, "always") }) { Text(tr("Всегда", "Always")) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = phoneTools, onClick = { phoneTools = !phoneTools }, label = { Text(tr("Телефон", "Phone"), fontSize = 12.sp) })
                if (running) FilterChip(selected = userControl, onClick = {
                    userControl = !userControl; MurfAccessibilityService.userHasControl = userControl
                }, label = { Text(if (userControl) tr("Экран у меня", "I have the screen") else tr("Взять экран", "Take screen"), fontSize = 12.sp) })
                Spacer(Modifier.weight(1f))
                val sel = MurfProviders.selected(context)
                TextButton(onClick = onProviders) { Text(sel?.second ?: tr("модель?", "model?"), fontSize = 12.sp, maxLines = 1) }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(tr("Задача для агента…", "Task for the agent…")) }, maxLines = 6)
                Spacer(Modifier.width(8.dp))
                if (running) Button(onClick = {
                    scope.launch { DshLocal.post(cookieJar, "stop", JSONObject()) }
                    MurfAccessibilityService.userHasControl = true; userControl = true
                }) { Text(tr("Стоп", "Stop")) }
                else Button(onClick = { val t = input; input = ""; send(t) }, enabled = input.isNotBlank()) { Text(tr("Отправить", "Send")) }
            }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun DshItemView(item: DshItem, onRetry: (String) -> Unit) {
    when (item) {
        is DshItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(10.dp)) {
                    SelectionContainer { Text(item.text) }
                    Text(tr("Повторить", "Retry"), Modifier.clickable { onRetry(item.text) }.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        is DshItem.Assistant -> SelectionContainer { Text(item.text, Modifier.padding(vertical = 2.dp)) }
        is DshItem.Thinking -> Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
        is DshItem.Tool -> {
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable { open = !open }, colors = CardDefaults.cardColors(
                containerColor = if (item.phone) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(8.dp)) {
                    val mark = when (item.status) { null -> "⏳"; "completed" -> "✓"; else -> "✗" }
                    Text((if (item.phone) "📱 " + (PhoneTool.of(item.shortName)?.title ?: item.shortName) else "🔧 " + item.name) + "  $mark",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    if (item.input.length > 2) Text(item.input.take(if (open) 4000 else 120), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    if (open && item.result != null) SelectionContainer { Text(item.result.take(8000), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
        is DshItem.Usage -> TurnUsageChip(item.turn)
        is DshItem.Error -> Text(item.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
