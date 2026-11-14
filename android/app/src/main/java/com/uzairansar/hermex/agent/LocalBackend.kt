package com.uzairansar.hermex.agent

import android.content.Context
import com.uzairansar.hermex.agent.embedded.EmbeddedRuntime
import com.uzairansar.hermex.agent.embedded.LocalRuntimeService
import com.uzairansar.hermex.ui.localization.localizedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Чем управляется локальная среда: встроенный proot MURF или Termux (запасной путь). */
enum class LocalBackendKind(val storage: String) {
    AUTO("auto"), EMBEDDED("embedded"), TERMUX("termux");
    companion object { fun of(s: String?) = entries.firstOrNull { it.storage == s } ?: AUTO }
}

/** Операции локальной среды, общие для обоих путей (LocalEnv и экраны вызывают только их). */
interface LocalBackend {
    val kind: LocalBackendKind
    /** Можно ли сейчас выполнять команды (Termux установлен и есть разрешение / proot под этот ABI). */
    fun ready(context: Context): Boolean
    fun notReadyReason(context: Context): String?
    suspend fun pushScripts(context: Context): TermuxResult
    /** `murf-local <args>`. */
    suspend fun murf(context: Context, args: List<String>, timeoutMs: Long): TermuxResult
    /** Сырой JSON `murf-local status`. */
    suspend fun statusJson(context: Context): Pair<JSONObject?, TermuxResult>
    fun install(context: Context)
    suspend fun start(context: Context): TermuxResult
    fun startDetached(context: Context)
    suspend fun stop(context: Context): TermuxResult
    fun login(context: Context, openTermux: Boolean)
    fun uninstall(context: Context)
    suspend fun startLogTail(context: Context, lines: Int): String
}

object LocalBackends {
    private const val PREFS = "murf_local"
    private const val KEY = "backend"

    fun preference(c: Context): LocalBackendKind =
        LocalBackendKind.of(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun setPreference(c: Context, k: LocalBackendKind) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, k.storage).apply()

    /** auto: встроенный, если proot есть под этот ABI; иначе Termux. */
    internal fun resolve(pref: LocalBackendKind, embeddedSupported: Boolean): LocalBackendKind = when (pref) {
        LocalBackendKind.AUTO -> if (embeddedSupported) LocalBackendKind.EMBEDDED else LocalBackendKind.TERMUX
        LocalBackendKind.EMBEDDED -> if (embeddedSupported) LocalBackendKind.EMBEDDED else LocalBackendKind.TERMUX
        LocalBackendKind.TERMUX -> LocalBackendKind.TERMUX
    }

    fun current(c: Context): LocalBackend =
        if (resolve(preference(c), EmbeddedRuntime.isSupported(c)) == LocalBackendKind.EMBEDDED) EmbeddedBackend else TermuxBackend
}

/** Прежний путь: Termux RUN_COMMAND + murf-local в ~/.murf-local. */
object TermuxBackend : LocalBackend {
    override val kind = LocalBackendKind.TERMUX
    override fun ready(context: Context) = Termux.isInstalled(context) && Termux.hasPermission(context)
    override fun notReadyReason(context: Context): String? = when {
        !Termux.isInstalled(context) -> context.localizedString("Install Termux (F-Droid/GitHub) — the local environment cannot start without it")
        !Termux.hasPermission(context) -> context.localizedString("Grant MURF the “Run commands in Termux” permission (Phone & Termux)")
        else -> null
    }

    override suspend fun pushScripts(context: Context): TermuxResult {
        val bytes = withContext(Dispatchers.IO) { context.assets.open("murf-local.tgz").use { it.readBytes() } }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        // один аргумент команды в Linux — не больше 128 КБ: передаём частями по 60 КБ и склеиваем в Termux
        b64.chunked(60_000).forEachIndexed { i, part ->
            val op = if (i == 0) ">" else ">>"
            val r = Termux.run(context, "mkdir -p ~/.murf-local && printf '%s' '$part' $op ~/.murf-local/bundle.b64", timeoutMs = 60_000)
            if (r.error != null || (r.exitCode ?: 0) != 0) return r
        }
        val cmd = "cd ~/.murf-local && base64 -d bundle.b64 > bundle.tgz && rm -f bundle.b64 && " +
            "tar -xzf bundle.tgz -C ~/.murf-local && rm -f bundle.tgz && " +
            "chmod 755 ~/.murf-local/murf-local && ln -sf ~/.murf-local/murf-local \$PREFIX/bin/murf-local && " +
            "echo murf-local \$(cat ~/.murf-local/VERSION)"
        return Termux.run(context, cmd, timeoutMs = 60_000)
    }

    override suspend fun murf(context: Context, args: List<String>, timeoutMs: Long): TermuxResult =
        Termux.run(context, "murf-local " + args.joinToString(" ") { Termux.shellQuote(it) }, timeoutMs = timeoutMs)

    override suspend fun statusJson(context: Context): Pair<JSONObject?, TermuxResult> {
        val r = Termux.run(context, "command -v murf-local >/dev/null || exit 127; murf-local status", timeoutMs = 60_000)
        val j = runCatching { JSONObject(r.stdout.trim().lines().last { it.startsWith("{") }) }.getOrNull()
        return j to r
    }

    override fun install(context: Context) = Termux.runDetached(
        context, "termux-wake-lock 2>/dev/null; murf-local install >> ~/.murf-local/logs/install.log 2>&1",
    )

    override suspend fun start(context: Context) =
        Termux.run(context, "termux-wake-lock 2>/dev/null; murf-local start", timeoutMs = 150_000)

    override fun startDetached(context: Context) = Termux.runDetached(
        context, "termux-wake-lock 2>/dev/null; murf-local start >> ~/.murf-local/logs/svc.log 2>&1",
    )

    override suspend fun stop(context: Context) = Termux.run(context, "murf-local stop", timeoutMs = 60_000)

    override fun login(context: Context, openTermux: Boolean) {
        val cmd = "murf-local login xai-oauth"
        if (openTermux) Termux.runSession(context, "$cmd; echo; read -p 'Enter — закрыть окно' _", openActivity = true)
        else Termux.runDetached(context, "$cmd >> ~/.murf-local/logs/login.log 2>&1")
    }

    override fun uninstall(context: Context) =
        Termux.runDetached(context, "murf-local uninstall --yes >> ~/.murf-local/logs/uninstall.log 2>&1")

    override suspend fun startLogTail(context: Context, lines: Int): String {
        val n = lines.coerceIn(10, 120)
        val r = Termux.run(
            context,
            "tail -n $n ~/.murf-local/logs/svc.log ~/.murf-local/logs/gateway.log ~/.murf-local/logs/install.log 2>/dev/null | tail -n $n",
            timeoutMs = 20_000,
        )
        return r.combined().trim().takeLast(4000)
    }
}

/** Встроенный путь: proot из APK, Ubuntu в данных MURF, фоновая служба LocalRuntimeService. */
object EmbeddedBackend : LocalBackend {
    override val kind = LocalBackendKind.EMBEDDED
    override fun ready(context: Context) = EmbeddedRuntime.isSupported(context)
    override fun notReadyReason(context: Context): String? =
        if (ready(context)) null else tr("Встроенная среда недоступна на этом процессоре — используйте Termux", "Embedded runtime is not available on this CPU — use Termux")

    override suspend fun pushScripts(context: Context): TermuxResult = withContext(Dispatchers.IO) {
        runCatching { TermuxResult(EmbeddedRuntime.syncScripts(context), "", 0, null) }
            .getOrElse { TermuxResult("", "", null, "scripts: ${it.message}") }
    }

    override suspend fun murf(context: Context, args: List<String>, timeoutMs: Long): TermuxResult {
        if (!EmbeddedRuntime.rootfsReady(context)) {
            return TermuxResult("", "", 2, tr("Сначала установка (Ubuntu ещё не распакована)", "Install first (Ubuntu is not unpacked yet)"))
        }
        pushScriptsIfMissing(context)
        return EmbeddedRuntime.murfLocal(context, args, timeoutMs)
    }

    private suspend fun pushScriptsIfMissing(context: Context) {
        if (!File(EmbeddedRuntime.hostDir(context), "murf-local").isFile) pushScripts(context)
    }

    override suspend fun statusJson(context: Context): Pair<JSONObject?, TermuxResult> {
        val running = LocalRuntimeService.running
        val installing = LocalRuntimeService.installing
        // пока Ubuntu нет или идёт установка — статус без запуска proot
        if (!EmbeddedRuntime.rootfsReady(context) || installing) {
            val j = EmbeddedRuntime.hostStatus(context, installing, running)
            if (EmbeddedRuntime.rootfsReady(context)) j.put("health", LocalEnv.health() ?: JSONObject.NULL)
            return j to TermuxResult(j.toString(), "", 0, null)
        }
        val r = murf(context, listOf("status"), 60_000)
        val j = runCatching { JSONObject(r.stdout.trim().lines().last { it.startsWith("{") }) }.getOrNull()
        j?.put("running", j.optBoolean("running") || running)
        j?.put("installing", j.optBoolean("installing") || installing)
        if (j != null && j.optLong("disk_mb", -1) < 0) withContext(Dispatchers.IO) { runCatching { EmbeddedRuntime.updateDiskUsage(context) } }
        return j to r
    }

    override fun install(context: Context) = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_INSTALL)

    override suspend fun start(context: Context): TermuxResult {
        startDetached(context)
        repeat(45) {
            if (LocalEnv.health() != null) return TermuxResult("запущено: ${LocalEnv.URL}/", "", 0, null)
            kotlinx.coroutines.delay(2_000)
        }
        return TermuxResult("службы стартуют (WebUI может подниматься 1–3 мин)", "", 0, null)
    }

    override fun startDetached(context: Context) = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_START)

    override suspend fun stop(context: Context): TermuxResult {
        LocalRuntimeService.send(context, LocalRuntimeService.ACTION_STOP)
        return TermuxResult(tr("остановка отправлена", "stop requested"), "", 0, null)
    }

    override fun login(context: Context, openTermux: Boolean) {
        // окна терминала нет: ссылка и код приходят через login-status (как в фоновом режиме Termux)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchCatching {
            val r = murf(context, listOf("login", "xai-oauth"), 900_000)
            EmbeddedRuntime.appendLog(context, "login.log", r.combined().takeLast(4000))
        }
    }

    override fun uninstall(context: Context) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchCatching {
            if (LocalRuntimeService.running || LocalRuntimeService.installing) {
                LocalRuntimeService.send(context, LocalRuntimeService.ACTION_STOP)
                var i = 0
                while (i++ < 40 && (LocalRuntimeService.running || LocalRuntimeService.installing)) kotlinx.coroutines.delay(500)
            }
            EmbeddedRuntime.deleteRootfs(context)
            EmbeddedRuntime.appendLog(context, "uninstall.log", "локальная среда удалена")
        }
    }

    override suspend fun startLogTail(context: Context, lines: Int): String = withContext(Dispatchers.IO) {
        val n = lines.coerceIn(10, 120)
        val logs = EmbeddedRuntime.logsDir(context)
        val inner = File(EmbeddedRuntime.rootfsDir(context), "root/.murf/logs/gateway.log")
        listOf(File(logs, "svc.log"), inner, File(logs, "install.log"), File(logs, "runtime.log"))
            .flatMap { f -> runCatching { f.readLines().takeLast(n) }.getOrDefault(emptyList()) }
            .takeLast(n).joinToString("\n").takeLast(4000)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) =
    launch { runCatching { block() } }

/** Короткая сводка встроенной среды для «Диагностики» (работает и без Ubuntu). */
object EmbeddedRuntimeInfo {
    fun summary(context: Context): String {
        val l = EmbeddedRuntime.layout(context)
        val ok = { b: Boolean -> if (b) "✅" else "❌" }
        return buildString {
            appendLine("MURF — встроенная среда (без Termux)")
            appendLine("${EmbeddedRuntime.androidInfo()} · ABI ${EmbeddedRuntime.abi() ?: "нет"}")
            appendLine("${ok(File(l.proot).canExecute())} proot: ${l.proot}")
            appendLine("${ok(File(l.loader).exists())} loader" + if (File(l.loader32).exists()) " (+32-бит)" else "")
            appendLine("${ok(EmbeddedRuntime.rootfsReady(context))} Ubuntu распакована" + if (EmbeddedRuntime.noSeccomp(context)) " (PROOT_NO_SECCOMP)" else "")
            appendLine("${ok(EmbeddedRuntime.installed(context))} Hermes и настройки на месте")
            appendLine("${ok(LocalRuntimeService.running)} служба MURF держит среду" + if (LocalRuntimeService.installing) " (идёт установка)" else "")
            append("свободно: ${EmbeddedRuntime.freeMb(context)} МБ")
        }
    }
}
