package com.uzairansar.hermex.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TermuxResult(val stdout: String, val stderr: String, val exitCode: Int?, val error: String?) {
    val ok: Boolean get() = error == null && (exitCode == null || exitCode == 0)
    fun combined(): String = buildString {
        append(stdout)
        if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append("[stderr]\n").append(stderr) }
        if (error != null) { if (isNotEmpty()) append("\n"); append(tr("[ошибка] ", "[error] ")).append(error) }
    }
}

/** Запуск команд в Termux через RUN_COMMAND (нужно allow-external-apps=true в ~/.termux/termux.properties). */
object Termux {
    const val PACKAGE = "com.termux"
    const val API_PACKAGE = "com.termux.api"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"

    const val SETUP_SCRIPT = "pkg update -y && pkg install -y termux-api curl rclone && termux-setup-storage; " +
        "mkdir -p ~/.termux && (grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || " +
        "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings && echo 'Готово: Termux настроен для MURF'"

    private val ids = AtomicInteger((System.currentTimeMillis() % 100000).toInt())
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()

    fun isInstalled(context: Context, pkg: String = PACKAGE): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun baseIntent(command: String, workdir: String?, background: Boolean): Intent =
        Intent(ACTION).setClassName(PACKAGE, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir ?: HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")

    private fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    /** Открыть команду в видимой сессии Termux (пользователь видит ввод/вывод). */
    fun runVisible(context: Context, command: String) {
        start(context, baseIntent(command, null, background = false))
    }

    /**
     * Долгая команда в отдельной сессии Termux (живёт, пока жива сессия; Termux держит foreground-service).
     * Важно: Termux запускает процесс сессии только при показе окна (проверено на 0.118.3) — поэтому openActivity=true.
     */
    fun runSession(context: Context, command: String, openActivity: Boolean) {
        start(context, baseIntent(command, null, background = false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", if (openActivity) "0" else "2"))
    }

    /** Фоновый запуск с ожиданием результата (stdout/stderr/exit code). */
    suspend fun run(context: Context, command: String, workdir: String? = null, timeoutMs: Long = 120_000): TermuxResult {
        if (!isInstalled(context)) return TermuxResult("", "", null, tr("Termux не установлен", "Termux is not installed"))
        if (!hasPermission(context)) return TermuxResult("", "", null, tr("Нет разрешения «Запуск команд в Termux» для приложения MURF", "MURF lacks the “Run commands in Termux” permission"))
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<TermuxResult>()
        waiting[id] = deferred
        val resultIntent = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, id, resultIntent, flags)
        val intent = baseIntent(command, workdir, background = true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        return try {
            start(context, intent)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: TermuxResult("", "", null, tr("Termux не ответил за ${timeoutMs / 1000} с (проверьте allow-external-apps и что Termux открывался хотя бы раз)", "Termux did not answer within ${timeoutMs / 1000} s (check allow-external-apps and that Termux was opened at least once)"))
        } catch (e: Exception) {
            TermuxResult("", "", null, tr("Не удалось запустить Termux: ${e.message}", "Could not start Termux: ${e.message}"))
        } finally {
            waiting.remove(id)
        }
    }

    internal fun deliver(id: Int, result: TermuxResult) {
        waiting[id]?.complete(result)
    }

    const val EXTRA_ID = "agent_termux_id"
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Termux.EXTRA_ID, -1)
        val bundle = intent.getBundleExtra("result")
        val res = if (bundle == null) {
            TermuxResult("", "", null, tr("Пустой ответ Termux", "Empty response from Termux"))
        } else {
            val err = bundle.getInt("err", -1)
            val errmsg = bundle.getString("errmsg")
            TermuxResult(
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                error = if (err > 0 /* Activity.RESULT_OK == -1 */ && !errmsg.isNullOrBlank()) errmsg else null,
            )
        }
        Termux.deliver(id, res)
    }
}
