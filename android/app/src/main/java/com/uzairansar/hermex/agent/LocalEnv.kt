package com.uzairansar.hermex.agent

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import com.uzairansar.hermex.data.repository.AddServerResult
import com.uzairansar.hermex.data.repository.AuthRepository
import com.uzairansar.hermex.data.secure.ServerAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Состояние локальной среды (ответ `murf-local status`). */
data class LocalStatus(
    val scripts: Boolean,            // murf-local есть в Termux
    val version: String = "",
    val installed: Boolean = false,
    val running: Boolean = false,
    val installing: Boolean = false,
    val diskMb: Long = -1,
    val freeMb: Long = -1,
    val pct: Int = 0,
    val step: String = "",
    val stepMsg: String = "",
    val stepState: String = "",
    val health: JSONObject? = null,
    val error: String? = null,
) {
    val webui get() = health?.optBoolean("webui") == true
    val api get() = health?.optBoolean("api") == true
    val desktop get() = health?.optBoolean("desktop") == true
    val providerLoggedIn get() = health?.optBoolean("provider_logged_in") == true
    val proxy get() = health?.optBoolean("proxy") == true
}

/**
 * Локальный режим MURF: Hermes Agent + WebUI + рабочий стол в proot Ubuntu внутри Termux.
 * Приложение управляет средой через Termux RUN_COMMAND (скрипт `murf-local`, лежит в assets/murf-local.tgz).
 */
object LocalEnv {
    const val PORT = 18080
    const val URL = "http://127.0.0.1:$PORT"
    const val DISPLAY_NAME = "Телефон (локально)"
    private const val PREFS = "murf_local"

    fun isLocal(url: String?): Boolean = url != null && (url.startsWith("http://127.0.0.1:$PORT") || url.startsWith("http://localhost:$PORT"))
    fun isLocal(url: HttpUrl?): Boolean = url != null && (url.host == "127.0.0.1" || url.host == "localhost") && url.port == PORT

    fun localAccount(auth: AuthRepository): ServerAccount? = auth.servers.value.servers.firstOrNull { isLocal(it.urlString) }
    fun serverAccount(auth: AuthRepository): ServerAccount? = auth.servers.value.servers.firstOrNull { !isLocal(it.urlString) }

    fun fallbackOffer(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("fallback_offer", true)
    fun setFallbackOffer(context: Context, v: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("fallback_offer", v).apply()

    private val quick = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    /** Быстрая проверка без Termux: отвечает ли локальный nginx/murf_api. */
    suspend fun health(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            quick.newCall(Request.Builder().url("$URL/murf/health").build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()) else null
            }
        }.getOrNull()
    }

    /** Доступен ли сервер (GET /health с коротким таймаутом). */
    suspend fun reachable(url: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url.newBuilder().encodedPath("/health").build()).build()).execute()
                .use { it.code in 200..499 }
        }.getOrDefault(false)
    }

    /** Кладёт свежие скрипты из APK в Termux (~/.murf-local) и ссылку $PREFIX/bin/murf-local. */
    suspend fun pushScripts(context: Context): TermuxResult {
        val bytes = withContext(Dispatchers.IO) { context.assets.open("murf-local.tgz").use { it.readBytes() } }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // Комплект с noVNC ~170 КБ (base64 ~230 КБ), а один аргумент команды в Linux — не больше 128 КБ:
        // передаём частями по 60 КБ и склеиваем в Termux.
        val parts = b64.chunked(60_000)
        parts.forEachIndexed { i, part ->
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

    /** `murf-local doctor`: короткий отчёт по всем частям среды (свежие скрипты кладём перед запуском). */
    suspend fun doctor(context: Context): String {
        pushScripts(context)
        return Termux.run(context, "murf-local doctor", timeoutMs = 120_000).combined().trim()
    }

    /** Перезапуск экрана агента (Xvnc + websockify) внутри уже работающей среды. */
    suspend fun restartScreen(context: Context): TermuxResult {
        pushScripts(context)
        return Termux.run(context, "murf-local restart-screen", timeoutMs = 150_000)
    }

    suspend fun screenLogs(context: Context): String =
        Termux.run(context, "murf-local logs screen 15", timeoutMs = 30_000).combined().trim()

    /** Подробное состояние экрана из работающей среды (?full=1: noVNC, Xvnc, VNC-сокеты). */
    suspend fun screenHealth(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("$URL/murf/health?full=1").build()).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body!!.string()) else null
                }
        }.getOrNull()
    }

    suspend fun status(context: Context): LocalStatus {
        val r = Termux.run(context, "command -v murf-local >/dev/null || exit 127; murf-local status", timeoutMs = 60_000)
        if (r.error != null) return LocalStatus(scripts = false, error = r.error)
        if (r.exitCode == 127) return LocalStatus(scripts = false)
        val j = runCatching { JSONObject(r.stdout.trim().lines().last { it.startsWith("{") }) }.getOrNull()
            ?: return LocalStatus(scripts = true, error = r.combined().take(400))
        val inner = j.optJSONObject("progress")
        val term = j.optJSONObject("termux_progress")
        // пока Ubuntu не готова — прогресс Termux-фазы, потом — внутренний
        val p = if (inner != null && (term == null || term.optString("state") == "done")) inner else term
        return LocalStatus(
            scripts = true,
            version = j.optString("version"),
            installed = j.optBoolean("installed"),
            running = j.optBoolean("running"),
            installing = j.optBoolean("installing"),
            diskMb = j.optLong("disk_mb", -1),
            freeMb = j.optLong("free_mb", -1),
            pct = p?.optInt("pct") ?: 0,
            step = p?.optString("step").orEmpty(),
            stepMsg = p?.optString("msg").orEmpty(),
            stepState = p?.optString("state").orEmpty(),
            health = j.optJSONObject("health"),
        )
    }

    /**
     * Установка без окна Termux: фоновый RUN_COMMAND + progress в `murf-local status`
     * (LocalRoute уже опрашивает pct/step). Повторный вызов продолжит с места остановки.
     */
    fun install(context: Context) = Termux.runDetached(
        context,
        "termux-wake-lock 2>/dev/null; murf-local install >> ~/.murf-local/logs/install.log 2>&1",
    )

    /**
     * Старт служб внутри MURF: без --fg и без Termux Activity.
     * `murf-local start` сам nohup-ит murf-svc и до ~90 с ждёт health.
     */
    suspend fun start(context: Context): TermuxResult =
        Termux.run(context, "termux-wake-lock 2>/dev/null; murf-local start", timeoutMs = 150_000)

    /** Fire-and-forget старт (для параллельного опроса логов в UI). */
    fun startDetached(context: Context) = Termux.runDetached(
        context,
        "termux-wake-lock 2>/dev/null; murf-local start >> ~/.murf-local/logs/svc.log 2>&1",
    )

    suspend fun stop(context: Context) = Termux.run(context, "murf-local stop", timeoutMs = 60_000)

    fun login(context: Context, openTermux: Boolean) {
        val cmd = "murf-local login xai-oauth"
        if (openTermux) {
            Termux.runSession(context, "$cmd; echo; read -p 'Enter — закрыть окно' _", openActivity = true)
        } else {
            Termux.runDetached(context, "$cmd >> ~/.murf-local/logs/login.log 2>&1")
        }
    }

    suspend fun loginStatus(context: Context): JSONObject? =
        Termux.run(context, "murf-local login-status", timeoutMs = 30_000).stdout.lines().lastOrNull { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    suspend fun netcheck(context: Context): JSONObject? =
        Termux.run(context, "murf-local netcheck", timeoutMs = 90_000).stdout.lines().lastOrNull { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    suspend fun setProxy(context: Context, url: String) =
        Termux.run(context, "murf-local proxy " + Termux.shellQuote(url.ifBlank { "off" }), timeoutMs = 60_000)

    fun uninstall(context: Context) = Termux.runDetached(context, "murf-local uninstall --yes >> ~/.murf-local/logs/uninstall.log 2>&1")

    suspend fun logs(context: Context, name: String) =
        Termux.run(context, "murf-local logs " + Termux.shellQuote(name) + " 60", timeoutMs = 30_000).combined()

    /** Хвост лога старта/служб для UI (без открытия Termux). */
    suspend fun startLogTail(context: Context, lines: Int = 40): String {
        val n = lines.coerceIn(10, 120)
        val r = Termux.run(
            context,
            "tail -n $n ~/.murf-local/logs/svc.log ~/.murf-local/logs/gateway.log ~/.murf-local/logs/install.log 2>/dev/null | tail -n $n",
            timeoutMs = 20_000,
        )
        return r.combined().trim().takeLast(4000)
    }

    /** Короткий статус шага для Harness/Local (юнит-тестируется). */
    internal fun startBusyLabel(elapsedSec: Int, phase: String): String =
        if (elapsedSec <= 0) phase else "$phase (${elapsedSec} с)"

    /** Сообщение вместо сырого OkHttp «Failed to connect» при мёртвом 127.0.0.1:18080. */
    internal fun localConnectUserMessage(t: Throwable): String? {
        val chain = generateSequence(t) { it.cause }.toList()
        if (chain.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException }) {
            return tr("Сначала дождитесь запуска среды", "Wait for the environment to finish starting")
        }
        val m = chain.mapNotNull { it.message }.joinToString(" ")
        if (m.contains("Failed to connect", ignoreCase = true) ||
            m.contains("Connection refused", ignoreCase = true) ||
            m.contains("failed to connect", ignoreCase = true)
        ) {
            return tr("Сначала дождитесь запуска среды", "Wait for the environment to finish starting")
        }
        return null
    }

    private fun rethrowLocalConnect(t: Throwable): Nothing {
        localConnectUserMessage(t)?.let { error(it) }
        throw t
    }

    /**
     * Переключиться на профиль «Телефон (локально)».
     * Если среда не запущена — стартует в фоне Termux (без окна) и ждёт health до ~180 с;
     * при уже известном профиле только activate() (как switchToServer), без живого login.
     * [onBusy] — шаг для UI; [onLog] — хвост журнала (обновляется во время ожидания).
     */
    suspend fun switchToLocal(
        context: Context,
        auth: AuthRepository,
        onBusy: (String) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        onBusy(tr("Проверка локальной среды", "Checking local environment"))
        var h = health()
        if (h == null) {
            if (!Termux.isInstalled(context)) {
                error(tr(
                    "Установите Termux (F-Droid/GitHub) — без него локальная среда не запустится",
                    "Install Termux (F-Droid/GitHub) — the local environment cannot start without it",
                ))
            }
            if (!Termux.hasPermission(context)) {
                error(tr(
                    "Дайте MURF разрешение «Запуск команд в Termux» (Телефон и Termux)",
                    "Grant MURF the “Run commands in Termux” permission (Phone & Termux)",
                ))
            }
            onBusy(tr("Синхронизация скриптов…", "Syncing scripts…"))
            val push = runCatching { pushScripts(context) }.getOrNull()
            if (push != null && push.error != null) {
                onLog(push.combined().takeLast(1500))
            }
            onBusy(tr("Запуск служб (в фоне, без окна Termux)…", "Starting services (background, no Termux window)…"))
            // Detached: UI остаётся в MURF; murf-local start nohup-ит svc и пишет svc.log
            startDetached(context)
            val deadline = System.currentTimeMillis() + 180_000L
            var waited = 0
            var lastLogAt = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                waited += 2
                onBusy(
                    startBusyLabel(
                        waited,
                        tr("Ожидание запуска среды", "Waiting for environment"),
                    ),
                )
                if (System.currentTimeMillis() - lastLogAt >= 4_000L) {
                    lastLogAt = System.currentTimeMillis()
                    runCatching { startLogTail(context, 30) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onLog)
                    if (waited in 6..14) {
                        val st = runCatching { status(context) }.getOrNull()
                        if (st != null && st.scripts && !st.installed) {
                            error(tr(
                                "Среда ещё не установлена — откройте «Локальная среда» → Установить (идёт в фоне, без окна Termux).",
                                "Environment is not installed yet — open Local environment → Install (runs in background, no Termux window).",
                            ))
                        }
                    }
                }
                h = health()
                if (h != null) break
            }
            if (h == null) {
                val tail = runCatching { startLogTail(context, 50) }.getOrDefault("")
                if (tail.isNotBlank()) onLog(tail)
                error(
                    tr(
                        "Среда не поднялась за 3 мин. Проверьте журнал ниже или «Локальная среда» → Диагностика.",
                        "Environment did not come up in 3 min. Check the log below or Local environment → Diagnostics.",
                    ),
                )
            }
        }

        val existing = localAccount(auth)
        if (existing != null) {
            onBusy(tr("Переключение на телефон", "Switching to phone"))
            auth.activate(existing.id)
            return@runCatching
        }

        onBusy(tr("Первый вход в локальный WebUI", "First login to local WebUI"))
        val r = Termux.run(context, "murf-local creds", timeoutMs = 60_000)
        r.error?.let { error(it) }
        val j = runCatching { JSONObject(r.stdout.lines().last { it.startsWith("{") }) }.getOrElse {
            error(tr("Локальная среда не установлена (нет пароля WebUI)", "Local environment is not installed (no WebUI password)"))
        }
        val pw = j.optString("password")
        if (pw.isBlank()) {
            error(tr("Локальная среда не установлена (нет пароля WebUI)", "Local environment is not installed (no WebUI password)"))
        }
        try {
            when (val res = auth.addServer(URL, pw, displayName = DISPLAY_NAME, initials = "ТЛ", headerLogoColorHex = "#4CAF50")) {
                is AddServerResult.Added -> Unit
                else -> error(tr("Локальный WebUI не принял пароль ($res)", "Local WebUI rejected the password ($res)"))
            }
        } catch (t: Throwable) {
            rethrowLocalConnect(t)
        }
    }

    suspend fun switchToServer(auth: AuthRepository): Result<Unit> = runCatching {
        val s = serverAccount(auth) ?: error(tr("Нет профиля сервера", "No server profile"))
        auth.activate(s.id)
    }

    /** Выгрузка настроек с сервера (через relay) и импорт в локальную среду. */
    suspend fun copyFromServer(context: Context, server: HttpUrl, jar: CookieJar, withSecrets: Boolean): String {
        val body = JSONObject().put("secrets", withSecrets).toString()
        val resp = withContext(Dispatchers.IO) {
            OkHttpClient.Builder().cookieJar(jar).connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(server.newBuilder().encodedPath("/phone/api/local/export").build())
                    .header("X-Agent-App", "1")
                    .post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (!r.isSuccessful) error(tr("сервер: HTTP ", "server: HTTP ") + r.code); JSONObject(r.body!!.string()) }
        }
        if (!resp.optBoolean("ok")) error(resp.optString("error", tr("ошибка выгрузки", "export failed")))
        val url = resp.getString("url")
        val r = Termux.run(context, "murf-local import " + Termux.shellQuote(url) + if (withSecrets) " --with-secrets" else "", timeoutMs = 300_000)
        if (r.error != null) error(r.error)
        return tr("Перенесено: ", "Transferred: ") + resp.optJSONArray("included")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } } +
            " (${resp.optLong("size") / 1024} " + tr("КБ", "KB") + ")\n" + r.stdout.trim().takeLast(600)
    }

    // ---------- батарея / фон ----------
    fun ignoringOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun requestIgnoreOptimizations(context: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        if (!tryStart(context, i)) tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppDetails(context: Context, pkg: String) =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    /** vivo / OriginOS: автозапуск и работа в фоне (разные версии прошивки — пробуем по очереди). */
    fun openVivoBackground(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
        )
        return candidates.any { tryStart(context, Intent().setComponent(it)) }
    }

    fun openDeveloperOptions(context: Context) =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) || tryStart(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openTermux(context: Context) =
        context.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let { tryStart(context, it) } ?: false

    fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun serverUrlOf(account: ServerAccount?): HttpUrl? = account?.urlString?.let { runCatching { it.toHttpUrl() }.getOrNull() }
}
