package com.uzairansar.hermex.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun http(jar: CookieJar) = OkHttpClient.Builder().cookieJar(jar)
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

private fun getJson(jar: CookieJar, url: HttpUrl): String =
    http(jar).newCall(Request.Builder().url(url).get().build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string()
    }

private fun postJson(jar: CookieJar, url: HttpUrl, body: String): String =
    http(jar).newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build())
        .execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CodeBox(code: String, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            TextButton(onClick = onCopy) { Text(tr("Копировать", "Copy")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneRoute(server: HttpUrl, cookieJar: CookieJar, onBack: () -> Unit) {
    val context = LocalContext.current
    val ui by PhoneBridge.ui.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val termuxInstalled = remember(refresh) { Termux.isInstalled(context) }
    val termuxApi = remember(refresh) { Termux.isInstalled(context, Termux.API_PACKAGE) }
    val termuxPerm = remember(refresh) { Termux.hasPermission(context) }
    var enabled by remember { mutableStateOf(PhoneBridgeSettings.enabled(context)) }
    var auto by remember { mutableStateOf(PhoneBridgeSettings.autoAllowed(context)) }
    var sms by remember { mutableStateOf(PhoneBridgeSettings.smsEnabled(context)) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Телефон и Termux", "Phone & Termux")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Update")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(tr("Мост «Телефон»", "Phone bridge")) {
                Text(tr("Когда мост включён, агент на сервере может попросить телефон выполнить действие: команду в Termux, фото, геопозицию, передачу файлов. Каждый запрос подтверждается здесь или в уведомлении, кроме разрешённых «всегда».", "When the bridge is on, the agent on the server can ask the phone to do something: a Termux command, a photo, location, file transfer. Each request is confirmed here or in a notification, except those allowed “always”."),
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) tr("Включён", "On") else tr("Выключен", "Off"), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(checked = enabled, onCheckedChange = { v ->
                        enabled = v
                        if (v) {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            PhoneBridge.start(context)
                        } else PhoneBridge.stop(context)
                    })
                }
                Text(
                    when {
                        !enabled -> tr("Агент не видит телефон.", "The agent can't see the phone.")
                        ui.connected -> tr("● Подключено к ${server.host} — агент видит телефон", "● Connected to ${server.host} — the agent sees the phone")
                        else -> tr("○ Подключение… ", "○ Connecting… ") + (ui.lastError ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (ui.pending.isNotEmpty()) {
                Section(tr("Ждут подтверждения", "Awaiting confirmation")) {
                    ui.pending.forEach { req ->
                        val tool = PhoneTool.of(req.tool)
                        Text("${tool?.title ?: req.tool}", fontWeight = FontWeight.Medium)
                        if (req.summary.isNotBlank()) Text(req.summary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { PhoneBridge.decide(context, req.id, "allow") }) { Text(tr("Разрешить", "Allow")) }
                            OutlinedButton(onClick = { PhoneBridge.decide(context, req.id, "deny") }) { Text(tr("Отклонить", "Deny")) }
                            TextButton(onClick = { PhoneBridge.decide(context, req.id, "always"); auto = PhoneBridgeSettings.autoAllowed(context) + req.tool }) { Text(tr("Всегда", "Always")) }
                        }
                    }
                }
            }

            Section(tr("Termux: состояние", "Termux: status")) {
                Text((if (termuxInstalled) "✓" else "✗") + tr(" Termux установлен", " Termux installed"))
                Text((if (termuxApi) "✓" else "✗") + tr(" Termux:API установлен (фото, геопозиция, буфер, SMS)", " Termux:API installed (photo, location, clipboard, SMS)"))
                Text((if (termuxPerm) "✓" else "✗") + tr(" Разрешение «Запуск команд в Termux»", " “Run commands in Termux” permission"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (termuxInstalled && !termuxPerm) Button(onClick = { permLauncher.launch(Termux.PERMISSION) }) { Text(tr("Выдать разрешение", "Grant permission")) }
                    if (termuxInstalled && termuxPerm) Button(onClick = {
                        testOutput = tr("Проверяю…", "Checking…")
                        scope.launch {
                            val r = Termux.run(context, "echo \"Termux OK: \$(uname -m), \$(date)\"; command -v termux-location >/dev/null && echo 'termux-api: есть' || echo 'termux-api: нет (pkg install termux-api)'; command -v curl >/dev/null && echo 'curl: есть' || echo 'curl: нет'", timeoutMs = 30_000)
                            testOutput = r.combined()
                        }
                    }) { Text(tr("Проверить", "Check")) }
                }
                testOutput?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }

            Section(tr("Настройка Termux (один раз)", "Termux setup (once)")) {
                Text(tr("1. Установите Termux и Termux:API из F-Droid (версия из Google Play устарела и не подходит).", "1. Install Termux and Termux:API from F-Droid (the Google Play version is outdated and won't work)."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(context, "https://f-droid.org/packages/com.termux/") }) { Text("Termux") }
                    OutlinedButton(onClick = { open(context, "https://f-droid.org/packages/com.termux.api/") }) { Text("Termux:API") }
                    TextButton(onClick = { open(context, "https://github.com/termux/termux-app/releases") }) { Text("GitHub") }
                }
                Text(tr("2. Откройте Termux и выполните (поставит termux-api, curl, rclone, доступ к памяти и разрешит внешние команды):", "2. Open Termux and run (installs termux-api, curl, rclone, storage access and allows external commands):"))
                CodeBox(Termux.SETUP_SCRIPT) { AgentShare.copy(context, Termux.SETUP_SCRIPT); AgentShare.toast(context, tr("Скопировано", "Copied")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        AgentShare.copy(context, Termux.SETUP_SCRIPT)
                        context.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let { context.startActivity(it) }
                            ?: AgentShare.toast(context, tr("Termux не установлен", "Termux is not installed"))
                    }) { Text(tr("Скопировать и открыть Termux", "Copy and open Termux")) }
                }
                Text(tr("3. Вернитесь сюда, нажмите «Выдать разрешение», затем «Проверить». 4. Включите мост выше.", "3. Come back here, tap “Grant permission”, then “Check”. 4. Turn on the bridge above."), style = MaterialTheme.typography.bodySmall)
            }

            Section(tr("Что разрешено без вопроса", "Allowed without asking")) {
                PhoneTool.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title)
                            if (t.risky) Text(tr("лучше подтверждать", "better to confirm"), style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = t.wire in auto, onCheckedChange = { v ->
                            PhoneBridgeSettings.setAutoAllowed(context, t.wire, v); auto = PhoneBridgeSettings.autoAllowed(context)
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Разрешить агенту запрашивать SMS", "Let the agent request SMS"), Modifier.weight(1f))
                    Switch(checked = sms, onCheckedChange = { sms = it; PhoneBridgeSettings.setSmsEnabled(context, it) })
                }
            }

            if (ui.log.isNotEmpty()) {
                Section(tr("Последние запросы", "Recent requests")) {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    ui.log.take(15).forEach { e ->
                        Text("${fmt.format(Date(e.time))}  ${PhoneTool.of(e.tool)?.title ?: e.tool} — ${e.state}", fontSize = 12.sp)
                        if (e.summary.isNotBlank()) Text(e.summary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun open(context: android.content.Context, url: String) =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveRoute(server: HttpUrl, cookieJar: CookieJar, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(tr("Проверяю…", "Checking…")) }
    var configured by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val authCmd = "pkg install -y rclone >/dev/null 2>&1; rclone authorize \"drive\" 2>&1 | tee \$HOME/.agent-drive-auth.txt"

    suspend fun sendToken(text: String) {
        busy = true
        status = tr("Отправляю токен на сервер…", "Sending token to server…")
        status = runCatching {
            val body = buildJsonObject { put("token", JsonPrimitive(text)) }.toString()
            val resp = withContext(Dispatchers.IO) { postJson(cookieJar, server.newBuilder().encodedPath("/phone/api/drive-token").build(), body) }
            val o = Json.parseToJsonElement(resp).jsonObject
            if (o["ok"].toString() == "true") tr("✓ Google Диск подключён к агенту", "✓ Google Drive connected to the agent") else tr("✗ Ошибка: ", "✗ Error: ") + o["error"]
        }.getOrElse { "✗ ${it.message}" }
        busy = false
        refresh++
    }

    LaunchedEffect(refresh) {
        runCatching {
            val s = withContext(Dispatchers.IO) { getJson(cookieJar, server.newBuilder().encodedPath("/phone/api/drive-status").build()) }
            val o = Json.parseToJsonElement(s).jsonObject
            configured = o["configured"].toString() == "true"
            if (!busy) status = if (configured) tr("✓ Агент подключён к Google Диску (remote gdrive:)", "✓ Agent is connected to Google Drive (remote gdrive:)") else tr("Агент ещё не подключён к Google Диску", "The agent is not connected to Google Drive yet")
        }.onFailure { if (!busy) status = tr("Не удалось узнать статус: ${it.message}", "Could not get status: ${it.message}") }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Google Диск", "Google Drive")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Update")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(tr("Статус", "Status")) { Text(status) }
            Section(tr("Сохранение из приложения", "Saving from the app")) {
                Text(tr("Любое сообщение, беседу (Markdown/PDF), файл из рабочей папки или снимок экрана агента можно отправить в Google Диск кнопкой «В Google Диск» — откроется окно приложения Диск, вход не нужен.", "Any message, conversation (Markdown/PDF), workspace file or agent screenshot can be sent to Google Drive with “To Google Drive” — the Drive app opens, no login needed."),
                    style = MaterialTheme.typography.bodySmall)
            }
            Section(tr("Доступ агента к Диску (rclone)", "Agent access to Drive (rclone)")) {
                Text(tr("Агент на сервере работает с вашим Диском через rclone. Нужна одна авторизация Google — пароль никуда не передаётся, на сервер уходит только токен rclone.", "The agent on the server works with your Drive via rclone. One Google authorization is needed — your password is never sent, only the rclone token goes to the server."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("Вариант А — на этом телефоне (нужен Termux с разрешением, см. «Телефон и Termux»):", "Option A — on this phone (needs Termux with permission, see “Phone & Termux”):"), fontWeight = FontWeight.Medium)
                Button(enabled = !busy, onClick = {
                    if (!Termux.isInstalled(context) || !Termux.hasPermission(context)) {
                        AgentShare.toast(context, tr("Сначала настройте Termux в разделе «Телефон»", "Set up Termux in “Phone” first"))
                    } else {
                        Termux.runVisible(context, authCmd)
                        status = tr("В Termux откроется ссылка Google. Войдите, разрешите доступ, затем вернитесь и нажмите «Отправить токен».", "Termux will open a Google link. Sign in, allow access, then come back and tap “Send token”.")
                    }
                }) { Text(tr("1. Войти в Google через Termux", "1. Sign in to Google via Termux")) }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        val r = Termux.run(context, "cat \$HOME/.agent-drive-auth.txt", timeoutMs = 30_000)
                        if (!r.stdout.contains("{")) status = tr("Токен не найден: завершите вход в Termux (шаг 1).", "Token not found: finish signing in in Termux (step 1).") else sendToken(r.stdout)
                    }
                }) { Text(tr("2. Отправить токен агенту", "2. Send token to the agent")) }
                Text(tr("Вариант Б — на компьютере: установите rclone и выполните", "Option B — on a computer: install rclone and run"), fontWeight = FontWeight.Medium)
                CodeBox("rclone authorize \"drive\"") { AgentShare.copy(context, "rclone authorize \"drive\""); AgentShare.toast(context, tr("Скопировано", "Copied")) }
                Text(tr("Скопируйте весь блок {…} между «Paste the following» и «End paste» и вставьте сюда:", "Copy the whole {…} block between “Paste the following” and “End paste” and paste it here:"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Токен rclone {…}", "rclone token {…}")) }, minLines = 2, maxLines = 5)
                Button(enabled = !busy && token.contains("{"), onClick = { scope.launch { sendToken(token); token = "" } }) { Text(tr("Отправить токен", "Send token")) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
