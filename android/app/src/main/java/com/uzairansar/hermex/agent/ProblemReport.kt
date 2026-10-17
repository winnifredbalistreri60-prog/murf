package com.uzairansar.hermex.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * MURF «Сообщить о проблеме»: описание + журнал приложения (logcat своего процесса) + сведения об устройстве.
 * Секреты, адреса и e-mail вычищаются ДО отправки (сервер чистит ещё раз). Отчёт уходит на ваш сервер
 * (/phone/api/report → ~/agent-phone/reports), оттуда его читает бот «MURF Отзывы». Никуда больше.
 */
object MurfRedact {
    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(authorization|cookie|set-cookie|x-api-key|proxy-authorization)\\s*[:=]\\s*[^\\n]+") to "$1: <скрыто>",
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}") to "Bearer <скрыто>",
        Regex("(?iu)(?<![\\p{L}\\p{N}_])([\\p{L}\\p{N}_.-]*(?:pass(?:word|wd)?|secret|token|api[_-]?key|session|sid|auth|key|pin|otp|парол[\\p{L}]*|токен|ключ|секрет|код))(\"?\\s*[:=]\\s*\"?)([^\\s\",;&]+)") to "$1$2<скрыто>",
        Regex("\\b(?:sk|pk|rk|ghp|gho|ghs|ghu|github_pat|xox[abpr]|glpat|AKIA)[-_A-Za-z0-9]{12,}\\b") to "<ключ>",
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b") to "<jwt>",
        Regex("\\b[0-9a-fA-F]{32,}\\b") to "<hex>",
        Regex("\\b[A-Za-z0-9+/_-]{40,}={0,2}") to "<b64>",
        Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+") to "<email>",
        Regex("(?i)\\b([a-z][a-z0-9+.-]*://)(?:[^\\s/@]+@)?[^\\s/:?#]+") to "$1<host>",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b") to "<ip>",
        Regex("(?iu)(?<![\\p{L}\\p{N}_.-])(?:[a-z0-9а-я-]+\\.)+(?:ru|com|net|org|io|dev|app|me|su|рф|info|xyz|cloud|online|site|pro)(?![\\p{L}\\p{N}_])") to "<host>",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (rx, repl) -> rx.replace(acc, repl) }
}

object ProblemReport {
    /** Журнал своего процесса (Android отдаёт приложению только его собственные строки). */
    fun collectLog(maxLines: Int = 1500): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString(), "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.takeLast(350_000)
    }.getOrElse { "(журнал недоступен: ${it.javaClass.simpleName})" }

    fun device(context: Context, serverKind: String): JSONObject {
        val dm = context.resources.displayMetrics
        return JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE.toString())
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT.toString())
            .put("locale", java.util.Locale.getDefault().toLanguageTag())
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}")
            .put("server_kind", serverKind)
    }

    fun build(context: Context, category: String, description: String, withLog: Boolean, serverKind: String): JSONObject =
        JSONObject()
            .put("category", category)
            .put("description", MurfRedact.redact(description.take(8000)))
            .put("log", if (withLog) MurfRedact.redact(collectLog()) else "")
            .put("device", device(context, serverKind))

    fun send(server: HttpUrl, jar: CookieJar, report: JSONObject): JSONObject =
        ServerUpdatesApi.post(server, jar, "/phone/api/report", report)
}

/** Диалог «Сообщить о проблеме». server/jar = null — нет входа: можно только скопировать отчёт. */
@Composable
fun ProblemReportDialog(server: HttpUrl?, jar: CookieJar?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("bug") }
    var text by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var sentOk by remember { mutableStateOf(false) }
    val serverKind = when {
        server == null -> "none"
        server.host == "127.0.0.1" || server.host == "localhost" -> "local"
        else -> "remote"
    }
    fun buildReport() = ProblemReport.build(context, category, text, withLog, serverKind)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Сообщить о проблеме", "Report a problem")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result != null) {
                    Text(result!!, color = if (sentOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (sentOk) return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("bug" to tr("Ошибка", "Bug"), "freeze" to tr("Зависание", "Freeze"), "idea" to tr("Идея", "Idea")).forEach { (k, l) ->
                        FilterChip(selected = category == k, onClick = { category = k }, label = { Text(l) })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(8000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).testTag("report_text"),
                    placeholder = { Text(tr("Что случилось? Что делали перед этим?", "What happened? What were you doing?")) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withLog, onCheckedChange = { withLog = it })
                    Text(tr("Приложить журнал приложения", "Attach the app log"), fontSize = 14.sp)
                }
                Text(
                    tr("Пароли, токены, ключи, cookie, адреса серверов, IP и e-mail вычищаются до отправки. Отчёт уходит только на ваш сервер, его читает бот «MURF Отзывы».",
                        "Passwords, tokens, keys, cookies, server addresses, IPs and e-mails are removed before sending. The report goes only to your server, where the “MURF Feedback” bot reads it."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        preview = withContext(Dispatchers.IO) { buildReport().toString(2) }
                        busy = false
                    }
                }, enabled = !busy) { Text(if (preview == null) tr("Посмотреть, что отправится", "Preview what is sent") else tr("Обновить просмотр", "Refresh preview")) }
                preview?.let {
                    SelectionContainer {
                        Text(it.take(20_000), fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            if (sentOk) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else if (server != null && jar != null) {
                TextButton(
                    enabled = !busy && (text.isNotBlank() || withLog),
                    onClick = {
                        busy = true; result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { ProblemReport.send(server, jar, buildReport()) } }
                            busy = false
                            r.onSuccess { j ->
                                if (j.optBoolean("ok")) { sentOk = true; result = tr("Отправлено, номер ${j.optString("id")}. Спасибо!", "Sent, id ${j.optString("id")}. Thank you!") }
                                else result = tr("Сервер не принял отчёт: ", "The server rejected the report: ") + j.optString("error", "?")
                            }.onFailure { result = tr("Не удалось отправить: ${it.message}. Можно скопировать отчёт.", "Could not send: ${it.message}. You can copy the report.") }
                        }
                    },
                    modifier = Modifier.testTag("report_send"),
                ) { Text(if (busy) tr("Отправка…", "Sending…") else tr("Отправить", "Send")) }
            } else {
                Text(tr("Нет входа на сервер", "Not signed in"), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        },
        dismissButton = {
            if (!sentOk) Row {
                TextButton(onClick = {
                    scope.launch {
                        val s = withContext(Dispatchers.IO) { buildReport().toString(2) }
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("MURF report", s))
                        result = tr("Отчёт скопирован (без секретов).", "Report copied (secrets removed).")
                    }
                }, enabled = !busy) { Text(tr("Копировать", "Copy")) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Отмена", "Cancel")) }
            }
        },
    )
}
