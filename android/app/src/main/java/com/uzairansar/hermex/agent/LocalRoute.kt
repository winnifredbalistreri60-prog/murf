package com.uzairansar.hermex.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.R
import com.uzairansar.hermex.data.repository.AuthRepository
import com.uzairansar.hermex.data.repository.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import org.json.JSONObject

@Composable
private fun LSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Check(ok: Boolean, label: String) {
    Text((if (ok) "✅ " else "⚪ ") + label, style = MaterialTheme.typography.bodyMedium)
}

private fun mb(v: Long) = when {
    v < 0 -> "…"
    v >= 1024 -> String.format(tr("%.1f ГБ", "%.1f GB"), v / 1024.0)
    else -> tr("$v МБ", "$v MB")
}

private fun netText(j: JSONObject?): String {
    if (j == null) return tr("Не удалось проверить (среда не установлена?)", "Check failed (environment not installed?)")
    fun c(code: String) = when {
        code == "403" -> tr("блок по региону (403)", "region block (403)")
        code.startsWith("2") || code.startsWith("3") || code in setOf("401", "404", "405") -> tr("доступен", "reachable")
        code == "000" || code.isEmpty() -> tr("нет соединения", "no connection")
        else -> "HTTP $code"
    }
    val d = j.optJSONObject("direct"); val p = j.optJSONObject("proxy")
    return buildString {
        append(tr("Напрямую: API xAI — ", "Direct: xAI API — ") + c(d?.optString("api").orEmpty()) + tr(", вход — ", ", login — ") + c(d?.optString("auth").orEmpty()))
        if (j.optBoolean("proxy_set")) append(tr("\nЧерез прокси: API — ", "\nVia proxy: API — ") + c(p?.optString("api").orEmpty()) + tr(", вход — ", ", login — ") + c(p?.optString("auth").orEmpty()))
        if (d?.optString("api") == "403" && !j.optBoolean("proxy_set"))
            append(tr("\n→ Включите VPN на телефоне (v2rayNG и т.п.) или укажите прокси ниже.", "\n→ Enable a VPN on the phone (v2rayNG etc.) or set a proxy below."))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocalRoute(auth: AuthRepository, cookieJar: CookieJar, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by auth.state.collectAsState()
    val servers by auth.servers.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<LocalStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var proxy by remember { mutableStateOf("") }
    var withSecrets by remember { mutableStateOf(false) }
    var confirmSecrets by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var loginInfo by remember { mutableStateOf<JSONObject?>(null) }
    var loginPolling by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }
    var fallback by remember { mutableStateOf(LocalEnv.fallbackOffer(context)) }
    val termuxOk = remember(refresh) { Termux.isInstalled(context) && Termux.hasPermission(context) }
    val activeUrl = (authState as? AuthState.LoggedIn)?.server
    val onLocal = LocalEnv.isLocal(activeUrl)
    val serverAcc = servers.servers.firstOrNull { !LocalEnv.isLocal(it.urlString) }
    val batteryOk = remember(refresh) { LocalEnv.ignoringOptimizations(context) }

    // опрос состояния: часто во время установки, редко в покое
    LaunchedEffect(refresh, termuxOk) {
        if (!termuxOk) return@LaunchedEffect
        while (true) {
            status = LocalEnv.status(context)
            delay(if (status?.installing == true || busy != null) 4000 else 15000)
        }
    }
    LaunchedEffect(loginPolling) {
        if (!loginPolling) return@LaunchedEffect
        repeat(300) {
            delay(3000)
            val j = LocalEnv.loginStatus(context)
            if (j != null) loginInfo = j
            val st = j?.optString("state")
            if (st == "ok" || st == "failed") { loginPolling = false; refresh++; return@LaunchedEffect }
        }
        loginPolling = false
    }

    fun act(label: String, block: suspend () -> String?) {
        busy = label; message = null
        scope.launch {
            message = runCatching { block() }.getOrElse { tr("Ошибка: ", "Error: ") + (it.message ?: it.toString()) }
            busy = null; refresh++
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Локальная среда", "Local environment")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Update")) } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp).testTag("local_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                tr("Агент целиком на телефоне: Ubuntu (proot) в Termux — Hermes Agent 0.21.5, WebUI, рабочий стол XFCE и браузер. ", "The whole agent on the phone: Ubuntu (proot) in Termux — Hermes Agent 0.21.5, WebUI, XFCE desktop and browser. ") +
                    tr("Сервер остаётся основным, телефон — запасной вариант. Docker и systemd здесь недоступны.", "The server stays primary, the phone is a fallback. Docker and systemd are not available here."),
                style = MaterialTheme.typography.bodySmall,
            )
            busy?.let { Text("⏳ $it…", color = MaterialTheme.colorScheme.primary) }
            message?.let { Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(it, Modifier.padding(10.dp), fontSize = 13.sp) } } }

            LSection(tr("Подключение", "Connection")) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = !onLocal, role = Role.RadioButton, enabled = busy == null && serverAcc != null) {
                        act(tr("Переключение на сервер", "Switching to server")) { LocalEnv.switchToServer(auth).getOrThrow(); tr("Подключено к серверу Hermes", "Connected to Hermes server") }
                    }.testTag("profile_server"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !onLocal, onClick = null)
                    Text(tr("Сервер Hermes", "Hermes server") + (serverAcc?.let { " — " + (LocalEnv.serverUrlOf(it)?.host ?: "") } ?: tr(" (не добавлен)", " (not added)")))
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = onLocal, role = Role.RadioButton, enabled = busy == null) {
                        act(tr("Переключение на телефон", "Switching to phone")) { LocalEnv.switchToLocal(context, auth).getOrThrow(); tr("Подключено к локальной среде ${LocalEnv.URL}", "Connected to local environment ${LocalEnv.URL}") }
                    }.testTag("profile_local"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = onLocal, onClick = null)
                    Text(tr("Телефон (локально) — ${LocalEnv.URL}", "Phone (local) — ${LocalEnv.URL}"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Предлагать телефон, если сервер недоступен", "Offer the phone when the server is unreachable"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = fallback, onCheckedChange = { fallback = it; LocalEnv.setFallbackOffer(context, it) })
                }
                Text(tr("Чаты, экран (смотреть/управлять) и настройки работают с выбранным профилем.", "Chats, screen (view/control) and settings use the selected profile."), style = MaterialTheme.typography.bodySmall)
            }

            LSection(tr("Состояние", "Status")) {
                if (!termuxOk) {
                    Text(tr("Нужен Termux (F-Droid/GitHub) и разрешение «Запуск команд в Termux» — настройте в разделе «Телефон и Termux».", "Requires Termux (F-Droid/GitHub) and the “Run commands in Termux” permission — set it up in “Phone & Termux”."),
                        color = MaterialTheme.colorScheme.error)
                } else {
                    val s = status
                    when {
                        s == null -> Text(tr("Проверка…", "Checking…"))
                        s.error != null -> Text("Termux: ${s.error}", color = MaterialTheme.colorScheme.error)
                        !s.scripts -> Text(tr("Скрипты MURF в Termux ещё не установлены — нажмите «Установить».", "MURF scripts are not installed in Termux yet — tap “Install”."))
                        else -> {
                            Check(s.installed, if (s.installed) tr("Среда установлена (murf-local ${s.version})", "Environment installed (murf-local ${s.version})") else tr("Среда не установлена", "Environment not installed"))
                            if (s.installing || (s.stepState == "running") || s.stepState == "error") {
                                Text(tr("Установка: ${s.pct}% — ${s.stepMsg}", "Installing: ${s.pct}% — ${s.stepMsg}"), color = if (s.stepState == "error") MaterialTheme.colorScheme.error else Color.Unspecified)
                                LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            Check(s.running, if (s.running) tr("Запущена", "Running") else tr("Остановлена", "Stopped"))
                            if (s.running || s.health != null) {
                                Check(s.webui, "WebUI"); Check(s.api, tr("API агента (127.0.0.1:8642)", "Agent API (127.0.0.1:8642)"))
                                Check(s.desktop, tr("Рабочий стол (экран)", "Desktop (screen)")); Check(s.providerLoggedIn, tr("Вход в провайдера xAI", "xAI provider login"))
                                if (s.proxy) Text(tr("Прокси для провайдера: включён", "Provider proxy: on"), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(tr("Занято средой: ${mb(s.diskMb)} · свободно на телефоне: ${mb(s.freeMb)}", "Used by environment: ${mb(s.diskMb)} · free on phone: ${mb(s.freeMb)}"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val s = status
                    Button(enabled = termuxOk && busy == null && s?.installing != true, modifier = Modifier.testTag("local_install"), onClick = {
                        act(tr("Подготовка установки", "Preparing installation")) {
                            val r = LocalEnv.pushScripts(context)
                            if (r.error != null || r.exitCode != 0) error(r.error ?: r.combined().take(300))
                            LocalEnv.install(context)
                            tr("Установка запущена в Termux (откроется окно с журналом). Займёт 20–60 мин и ~4 ГБ. Вернитесь в MURF — прогресс виден здесь; сессию Termux не закрывайте.", "Installation started in Termux (a log window opens). Takes 20–60 min and ~4 GB. Return to MURF — progress is shown here; don't close the Termux session.")
                        }
                    }) { Text(if (s?.installed == true) tr("Обновить", "Update") else tr("Установить", "Install")) }
                    OutlinedButton(enabled = termuxOk && busy == null && s?.installed == true && !s.running, onClick = {
                        act(tr("Запуск", "Starting")) { LocalEnv.pushScripts(context); LocalEnv.start(context); delay(4000); tr("Службы запускаются в сессии Termux (WebUI поднимается 1–3 мин). Вернитесь в MURF; сессию не закрывайте.", "Services start in a Termux session (WebUI takes 1–3 min). Return to MURF; don't close the session.") }
                    }) { Text(tr("Запустить", "Start")) }
                    OutlinedButton(enabled = termuxOk && busy == null && s?.running == true, onClick = {
                        act(tr("Остановка", "Stopping")) { LocalEnv.stop(context).combined().take(300) }
                    }) { Text(tr("Остановить", "Stop")) }
                    TextButton(enabled = termuxOk, onClick = { act(tr("Журнал", "Log")) { logText = LocalEnv.logs(context, if (status?.installed == true) "gateway" else "install"); null } }) { Text(tr("Журнал", "Log")) }
                    TextButton(onClick = { LocalEnv.openTermux(context) }) { Text(tr("Открыть Termux", "Open Termux")) }
                }
            }

            LSection(tr("Провайдер (вход из среды)", "Provider (login from the environment)")) {
                Text(tr("Вход xAI выполняется внутри локальной среды (device-code): откройте ссылку, подтвердите код в браузере телефона.", "xAI login runs inside the local environment (device code): open the link and confirm the code in the phone browser."),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = termuxOk && status?.installed == true && busy == null, modifier = Modifier.testTag("local_login"), onClick = {
                        loginInfo = null; LocalEnv.login(context, openTermux = false); loginPolling = true
                    }) { Text(tr("Войти в провайдера", "Log in to provider")) }
                    OutlinedButton(enabled = termuxOk && status?.installed == true && busy == null, onClick = {
                        act(tr("Проверка сети", "Network check")) { netText(LocalEnv.netcheck(context)) }
                    }) { Text(tr("Проверить доступ", "Check access")) }
                }
                loginInfo?.let { j ->
                    val url = j.optString("url"); val code = j.optString("code")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when (j.optString("state")) { "ok" -> tr("✅ Вход выполнен", "✅ Logged in"); "failed" -> tr("❌ Вход не удался", "❌ Login failed"); else -> tr("Ожидание подтверждения…", "Waiting for confirmation…") })
                            if (code.isNotBlank()) SelectionContainer { Text(tr("Код: $code", "Code: $code"), fontFamily = FontFamily.Monospace, fontSize = 18.sp) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (url.isNotBlank()) OutlinedButton(onClick = { LocalEnv.tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }) { Text(tr("Открыть ссылку", "Open link")) }
                                if (code.isNotBlank()) TextButton(onClick = { AgentShare.copy(context, code); AgentShare.toast(context, tr("Код скопирован", "Code copied")) }) { Text(tr("Копировать код", "Copy code")) }
                                TextButton(onClick = { LocalEnv.openTermux(context) }) { Text(tr("Показать в Termux", "Show in Termux")) }
                            }
                            if (url.isBlank()) Text(j.optString("tail").takeLast(300), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
                OutlinedTextField(value = proxy, onValueChange = { proxy = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Прокси (необязательно)", "Proxy (optional)")) }, placeholder = { Text("socks5h://127.0.0.1:10808") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = termuxOk && status?.installed == true && busy == null, onClick = {
                        act(tr("Прокси", "Proxy")) { LocalEnv.setProxy(context, proxy.trim()).combined().take(200) }
                    }) { Text(tr("Сохранить прокси", "Save proxy")) }
                    TextButton(enabled = termuxOk && status?.installed == true && busy == null, onClick = {
                        proxy = ""; act(tr("Прокси", "Proxy")) { LocalEnv.setProxy(context, "off").combined().take(200) }
                    }) { Text(tr("Выключить", "Turn off")) }
                }
                Text(tr("Из Ирана api.x.ai отвечает 403 — нужен VPN на телефоне (весь трафик, включая Termux) или прокси.", "In some regions api.x.ai answers 403 — use a VPN on the phone (all traffic, including Termux) or a proxy."),
                    style = MaterialTheme.typography.bodySmall)
            }

            LSection(tr("Скопировать настройки с сервера", "Copy settings from server")) {
                Text(tr("Переносит config.yaml (модель, провайдер), навыки (skills), память и SOUL.md. Серверный MCP agent_tools не переносится.", "Transfers config.yaml (model, provider), skills, memory and SOUL.md. The server-only agent_tools MCP is not transferred."),
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withSecrets, onCheckedChange = { if (it) confirmSecrets = true else withSecrets = false })
                    Text(tr("Вместе с секретами (вход xAI и API-ключи)", "Include secrets (xAI login and API keys)"), style = MaterialTheme.typography.bodyMedium)
                }
                Button(enabled = termuxOk && status?.installed == true && busy == null && serverAcc != null, onClick = {
                    val su = LocalEnv.serverUrlOf(serverAcc)!!
                    act(tr("Перенос настроек", "Transferring settings")) { LocalEnv.copyFromServer(context, su, cookieJar, withSecrets) }
                }) { Text(tr("Скопировать с сервера", "Copy from server")) }
                if (serverAcc == null) Text(tr("Нет профиля сервера.", "No server profile."), style = MaterialTheme.typography.bodySmall)
            }

            LSection(tr("Батарея и фон (важно)", "Battery & background (important)")) {
                Text(tr("Android/OriginOS выгружает фоновые процессы. Чтобы среда не умирала:\n", "Android/OriginOS kills background processes. To keep the environment alive:\n") +
                    tr("1) MURF и Termux — «Без ограничений» в батарее; 2) vivo: разрешить автозапуск и «высокое потребление в фоне»;\n", "1) MURF and Termux — “Unrestricted” battery; 2) vivo: allow autostart and “high background power use”;\n") +
                    tr("3) Для разработчиков → «Отключить ограничения дочерних процессов» (иначе Android 12+ убивает процессы Termux сверх 32);\n", "3) Developer options → “Disable child process restrictions” (otherwise Android 12+ kills Termux processes beyond 32);\n") +
                    tr("4) уведомление Termux с «Acquire wakelock» оставляйте.", "4) keep the Termux notification with “Acquire wakelock”."), style = MaterialTheme.typography.bodySmall)
                Check(batteryOk, tr("MURF: без оптимизации батареи", "MURF: no battery optimization"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { LocalEnv.requestIgnoreOptimizations(context); refresh++ }) { Text(tr("MURF: батарея", "MURF: battery")) }
                    OutlinedButton(onClick = { LocalEnv.openAppDetails(context, Termux.PACKAGE) }) { Text(tr("Termux: настройки", "Termux: settings")) }
                    OutlinedButton(onClick = { if (!LocalEnv.openVivoBackground(context)) AgentShare.toast(context, tr("Экран vivo не найден — откройте «i Менеджер» → Управление приложениями", "vivo screen not found — open “i Manager” → App management")) }) { Text(tr("vivo: автозапуск/фон", "vivo: autostart/background")) }
                    OutlinedButton(onClick = { LocalEnv.openDeveloperOptions(context) }) { Text(tr("Для разработчиков", "Developer options")) }
                }
            }

            LSection(tr("Удаление", "Removal")) {
                Text(tr("Удаляет Ubuntu со всем содержимым (агент, вход, чаты локальной среды). Профиль на сервере не затрагивается.", "Removes Ubuntu with everything inside (agent, login, local chats). The server profile is not affected."),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = termuxOk && status?.rootfsPresent() == true && busy == null, onClick = { confirmUninstall = true }) { Text(tr("Удалить локальную среду", "Remove local environment")) }
            }
        }
    }

    if (confirmSecrets) AlertDialog(
        onDismissRequest = { confirmSecrets = false },
        title = { Text(tr("Перенести секреты?", "Transfer secrets?")) },
        text = { Text(tr("На телефон будут скопированы токены входа xAI (auth.json) и API-ключи провайдеров с сервера. ", "xAI login tokens (auth.json) and provider API keys will be copied from the server to the phone. ") +
            tr("Любой, кто получит доступ к телефону/Termux, сможет ими воспользоваться. Токен OAuth может обновиться на одном устройстве и ", "Anyone with access to the phone/Termux can use them. An OAuth token may refresh on one device and ") +
            tr("перестать работать на другом — тогда просто войдите заново. Продолжить?", "stop working on the other — then just log in again. Continue?")) },
        confirmButton = { TextButton(onClick = { withSecrets = true; confirmSecrets = false }) { Text(tr("Да, перенести", "Yes, transfer")) } },
        dismissButton = { TextButton(onClick = { confirmSecrets = false }) { Text(tr("Отмена", "Cancel")) } },
    )
    if (confirmUninstall) AlertDialog(
        onDismissRequest = { confirmUninstall = false },
        title = { Text(tr("Удалить локальную среду?", "Remove local environment?")) },
        text = { Text(tr("Будет удалена Ubuntu (~4 ГБ) со всеми данными локального агента.", "Ubuntu (~4 GB) will be removed with all local agent data.")) },
        confirmButton = { TextButton(onClick = {
            confirmUninstall = false
            scope.launch {
                if (onLocal) LocalEnv.switchToServer(auth)
                LocalEnv.uninstall(context); message = tr("Удаление запущено в Termux", "Removal started in Termux"); refresh++
            }
        }) { Text(tr("Удалить", "Remove")) } },
        dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(tr("Отмена", "Cancel")) } },
    )
    logText?.let { t -> AlertDialog(
        onDismissRequest = { logText = null },
        title = { Text(tr("Журнал", "Log")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainer { Text(t, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } } },
        confirmButton = { TextButton(onClick = { logText = null }) { Text(tr("Закрыть", "Close")) } },
    ) }
}

private fun LocalStatus.rootfsPresent() = scripts && (installed || diskMb > 0 || installing || stepState.isNotEmpty())
