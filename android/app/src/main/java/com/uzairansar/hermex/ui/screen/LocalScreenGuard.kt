package com.uzairansar.hermex.ui.screen

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.agent.AgentShare
import com.uzairansar.hermex.agent.LocalEnv
import com.uzairansar.hermex.agent.Termux
import com.uzairansar.hermex.agent.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import org.json.JSONObject
import kotlin.coroutines.resume

/** Состояние страницы экрана: window.murfState (index.html) — js выполнился?, есть ли соединение, сколько попыток. */
private suspend fun pageState(wv: WebView?): JSONObject? {
    wv ?: return null
    val raw = suspendCancellableCoroutine<String?> { c ->
        runCatching { wv.evaluateJavascript("JSON.stringify(window.murfState||null)") { c.resume(it) } }.onFailure { c.resume(null) }
    } ?: return null
    // evaluateJavascript возвращает JSON-строку в кавычках
    return runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
}

/** Что именно не работает — по состоянию страницы и /murf/health?full=1 из локальной среды. */
internal fun diagnoseLocalScreen(page: JSONObject?, health: JSONObject?): String {
    val scr = health?.optJSONObject("screen")
    return when {
        health == null -> tr("Локальная среда не отвечает (nginx 127.0.0.1:18080). Запустите её в «Локальная среда».",
            "The local environment does not respond (nginx 127.0.0.1:18080). Start it in “Local environment”.")
        page == null -> tr("Страница экрана не открылась.", "The screen page did not open.")
        !page.optBoolean("js") || scr?.optBoolean("novnc", true) == false ->
            tr("Не загрузились файлы экрана (noVNC). Обновите локальную среду: «Локальная среда» → «Обновить».",
                "Screen files (noVNC) did not load. Update the local environment: “Local environment” → “Update”.")
        scr != null && !scr.optBoolean("xvnc") -> tr("Рабочий стол агента (Xvnc) не запущен.", "The agent desktop (Xvnc) is not running.")
        !health.optBoolean("screen_view") -> tr("Не запущен websockify экрана (порт 18081).", "Screen websockify (port 18081) is not running.")
        scr != null && !scr.optBoolean("rfb_sock") -> tr("VNC рабочего стола не отвечает.", "The desktop VNC does not respond.")
        scr != null && !scr.optBoolean("view_sock") -> tr("Прокси просмотра (view.sock) не отвечает.", "The view proxy (view.sock) does not respond.")
        else -> tr("WebSocket экрана не подключается", "The screen WebSocket does not connect") +
            (page.optString("lastError").takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }
}

/**
 * Для локального профиля: если за 15 с экран не подключился — карточка с причиной и кнопками
 * «Перезапустить экран» / «Показать журнал» вместо бесконечного «Подключение…».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalScreenGuard(server: HttpUrl, webView: () -> WebView?, onReload: () -> Unit, modifier: Modifier = Modifier, key: Any? = null) {
    if (!LocalEnv.isLocal(server)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, round) {
        problem = null; dismissed = false
        delay(15_000)
        var badSince = 0
        while (true) {
            val page = pageState(webView())
            if (page?.optBoolean("connected") == true) { problem = null; badSince = 0 }
            else {
                badSince++
                if (problem == null || badSince % 3 == 0) problem = diagnoseLocalScreen(page, LocalEnv.screenHealth())
            }
            delay(5_000)
        }
    }
    val p = problem
    if (p != null && !dismissed) {
        Box(modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xF01C1C22), RoundedCornerShape(14.dp)).padding(12.dp).testTag("local_screen_problem"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("Экран не подключился", "The screen did not connect"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(p, color = Color(0xFFE0E0E6), fontSize = 13.sp, lineHeight = 17.sp)
                busy?.let { Text("⏳ $it…", color = Color(0xFF8AB4FF), fontSize = 12.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null, onClick = {
                        if (!Termux.hasPermission(context)) { AgentShare.toast(context, tr("Нет разрешения на команды Termux — «Телефон и Termux»", "No Termux command permission — see “Phone & Termux”")); return@Button }
                        busy = tr("Перезапуск экрана (до 1,5 мин)", "Restarting the screen (up to 1.5 min)")
                        scope.launch {
                            val r = LocalEnv.restartScreen(context)
                            busy = null
                            AgentShare.toast(context, r.stdout.lines().lastOrNull { it.isNotBlank() } ?: r.combined().take(200))
                            onReload(); round++
                        }
                    }) { Text(tr("Перезапустить экран", "Restart screen")) }
                    OutlinedButton(enabled = busy == null, onClick = {
                        busy = tr("Журнал", "Log")
                        scope.launch { log = LocalEnv.screenLogs(context); busy = null }
                    }) { Text(tr("Показать журнал", "Show log")) }
                    TextButton(onClick = { dismissed = true }) { Text(tr("Скрыть", "Hide")) }
                }
                Text(tr("Полная проверка: «Локальная среда» → «Диагностика».", "Full check: “Local environment” → “Diagnostics”."), color = Color(0xFF9A9AA2), fontSize = 11.sp)
            }
        }
    }
    log?.let { t ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text(tr("Журнал экрана", "Screen log")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainer { Text(t, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } } },
            confirmButton = { TextButton(onClick = { log = null }) { Text(tr("Закрыть", "Close")) } },
            dismissButton = { TextButton(onClick = { AgentShare.copy(context, t); AgentShare.toast(context, tr("Скопировано", "Copied")) }) { Text(tr("Копировать", "Copy")) } },
        )
    }
}
