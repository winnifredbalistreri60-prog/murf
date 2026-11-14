package com.uzairansar.hermex.agent.embedded

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.uzairansar.hermex.agent.PhoneBridgeService
import com.uzairansar.hermex.agent.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground-служба встроенной среды (вместо сессии Termux + termux-wake-lock).
 *  - INSTALL: RootfsInstaller (загрузка/распаковка Ubuntu) → `murf-local install` внутри proot;
 *  - START: держит процесс `proot … murf-local svc-run` (супервизор murf-svc), журнал host/logs/svc.log;
 *  - STOP: `murf-local stop`, затем SIGTERM/SIGKILL proot (--kill-on-exit гасит всех потомков).
 * Тип specialUse: dataSync на Android 15+ ограничен 6 ч в сутки, а локальный агент работает часами.
 */
class LocalRuntimeService : Service() {
    enum class Phase { IDLE, INSTALLING, STARTING, RUNNING, STOPPING, ERROR }
    /** op: какая операция дала это состояние ([OP_INSTALL]/[OP_UPDATE] — установка/обновление), для экрана и тестов. */
    data class State(val phase: Phase = Phase.IDLE, val message: String = "", val op: String = "") {
        val installOp: Boolean get() = op == OP_INSTALL || op == OP_UPDATE
        val update: Boolean get() = op == OP_UPDATE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var installJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(tr("MURF: локальная среда", "MURF: local environment"), state.value.message.ifBlank { tr("Подготовка…", "Preparing…") })
        when (intent?.action) {
            ACTION_INSTALL -> startInstall()
            ACTION_START -> startServices()
            ACTION_STOP -> scope.launch { stopAll(); stopSelfIfIdle() }
            else -> if (process?.isAlive != true && installJob?.isActive != true) stopSelfIfIdle()
        }
        return START_NOT_STICKY // после убийства системой не перезапускаемся сами: пользователь нажмёт «Запустить»
    }

    /** «Стоп» во время установки: код 137 от SIGKILL — не ошибка, а отмена. */
    @Volatile private var stopRequested = false

    private fun startInstall() {
        if (installJob?.isActive == true) return
        if (process?.isAlive == true) { set(Phase.RUNNING, tr("Сначала остановите среду", "Stop the environment first")); return }
        stopRequested = false
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID + 1) }
        acquireWake()
        val upd = EmbeddedRuntime.installed(this)
        installOp = if (upd) OP_UPDATE else OP_INSTALL
        installJob = scope.launch {
            set(Phase.INSTALLING, if (upd) tr("Обновление: проверка Ubuntu", "Updating: checking Ubuntu") else tr("Установка: загрузка Ubuntu", "Installing: downloading Ubuntu"))
            try {
                EmbeddedRuntime.syncScripts(this@LocalRuntimeService)
                runCatching { EmbeddedRuntime.emptyTrash(this@LocalRuntimeService) }
                RootfsInstaller(this@LocalRuntimeService).run { !isActive }
                selfTest()
                EmbeddedRuntime.writeProgress(this@LocalRuntimeService, "done", 5, "inner", tr("Установка внутри Ubuntu", "Installing inside Ubuntu"))
                set(Phase.INSTALLING, if (upd) tr("Обновление внутри Ubuntu (обычно несколько минут)", "Updating inside Ubuntu (usually a few minutes)")
                    else tr("Установка внутри Ubuntu (20–60 мин)", "Installing inside Ubuntu (20–60 min)"))
                val p = EmbeddedRuntime.processBuilder(this@LocalRuntimeService, EmbeddedRuntime.murfLocalCommand(listOf("install")))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File(EmbeddedRuntime.logsDir(this@LocalRuntimeService), "runtime.log")))
                    .start()
                installProcess = p
                p.outputStream.close()
                // «Занято средой» растёт во время установки: пересчёт раз в 45 с, а не только в конце
                while (!p.waitFor(45, TimeUnit.SECONDS)) runCatching { EmbeddedRuntime.updateDiskUsage(this@LocalRuntimeService) }
                val rc = p.exitValue()
                installProcess = null
                runCatching { EmbeddedRuntime.emptyTrash(this@LocalRuntimeService) }
                EmbeddedRuntime.updateDiskUsage(this@LocalRuntimeService)
                if (stopRequested) set(Phase.IDLE, if (upd) tr("Обновление прервано", "Update cancelled") else tr("Установка прервана", "Installation cancelled"))
                else if (rc == 0) set(Phase.IDLE, if (upd) tr("Обновление завершено", "Update finished") else tr("Установка завершена", "Installation finished"))
                else set(Phase.ERROR, if (upd) tr("Ошибка обновления (код $rc), см. журнал", "Update failed (code $rc), see log")
                    else tr("Ошибка установки (код $rc), см. журнал", "Installation failed (code $rc), see log"))
            } catch (e: java.util.concurrent.CancellationException) {
                set(Phase.IDLE, if (upd) tr("Обновление прервано", "Update cancelled") else tr("Установка прервана", "Installation cancelled"))
            } catch (e: Exception) {
                set(Phase.ERROR, e.message ?: e.toString())
                EmbeddedRuntime.appendLog(this@LocalRuntimeService, "install.log", "ошибка: $e")
            } finally {
                installProcess?.let { killTree(it) }; installProcess = null
                releaseWake(); stopSelfIfIdle(ignoreInstallJob = true)
                if (_state.value.phase == Phase.ERROR) notifyResult(_state.value.message, upd)
                installOp = ""
            }
        }
    }

    /** Первый запуск proot; при сбое — повтор без seccomp (старые ядра), флаг host/noseccomp. */
    private suspend fun selfTest() {
        val c = this
        File(EmbeddedRuntime.hostDir(c), "noseccomp").delete()
        var r = EmbeddedRuntime.exec(c, listOf("/bin/sh", "-c", "uname -a; cat /etc/os-release | head -2"), 60_000)
        if (!r.ok) {
            EmbeddedRuntime.appendLog(c, "install.log", "proot self-test failed: ${r.combined().take(500)}; retry PROOT_NO_SECCOMP=1")
            File(EmbeddedRuntime.hostDir(c), "noseccomp").writeText("1\n")
            r = EmbeddedRuntime.exec(c, listOf("/bin/sh", "-c", "uname -a"), 60_000)
            if (!r.ok) throw RootfsInstallException("proot не запускается: " + r.combined().take(300))
        }
        EmbeddedRuntime.appendLog(c, "install.log", "proot ok: " + r.stdout.trim().replace('\n', ' '))
    }

    /** Текущая операция для [State.op]: пока идёт установка/обновление — OP_INSTALL/OP_UPDATE. */
    @Volatile private var installOp = ""

    private fun startServices() {
        if (process?.isAlive == true) { set(Phase.RUNNING, tr("Среда работает", "Environment is running")); return }
        if (installJob?.isActive == true) return
        if (!EmbeddedRuntime.installed(this)) { set(Phase.ERROR, tr("Среда не установлена", "Environment is not installed")); stopSelfIfIdle(); return }
        acquireWake()
        set(Phase.STARTING, tr("Запуск служб…", "Starting services…"))
        scope.launch {
            try {
                EmbeddedRuntime.syncScripts(this@LocalRuntimeService)
                val log = File(EmbeddedRuntime.logsDir(this@LocalRuntimeService), "svc.log")
                if (log.length() > 5_000_000) log.renameTo(File(log.path + ".1"))
                val p = EmbeddedRuntime.processBuilder(this@LocalRuntimeService, EmbeddedRuntime.murfLocalCommand(listOf("svc-run")))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .start()
                p.outputStream.close()
                process = p
                set(Phase.RUNNING, tr("Среда работает: http://127.0.0.1:18080", "Running: http://127.0.0.1:18080"))
                val rc = p.waitFor()
                if (process === p) {
                    process = null
                    if (state.value.phase != Phase.STOPPING) set(Phase.ERROR, tr("Среда завершилась (код $rc)", "Environment exited (code $rc)"))
                }
            } catch (e: Exception) {
                set(Phase.ERROR, "proot: ${e.message}")
            } finally {
                // при «Стоп» службу гасит сам stopAll (иначе onDestroy обрывал бы его на «Остановка…»)
                if (process == null && _state.value.phase != Phase.STOPPING) { releaseWake(); stopSelfIfIdle() }
            }
        }
    }

    private suspend fun stopAll() {
        set(Phase.STOPPING, tr("Остановка…", "Stopping…"))
        stopRequested = true
        installJob?.cancel()
        installProcess?.let { killTree(it) }; installProcess = null
        val p = process
        if (EmbeddedRuntime.rootfsReady(this)) {
            runCatching { EmbeddedRuntime.murfLocal(this, listOf("stop"), 60_000) }
        }
        if (p != null) killTree(p, graceSec = 10)
        process = null
        releaseWake()
        set(Phase.IDLE, tr("Остановлено", "Stopped"))
    }

    /**
     * proot на SIGTERM не завершается, пока живы трассируемые процессы (проверено на эмуляторе:
     * установка продолжалась после «Стоп»). На Android Process.destroyForcibly() тоже шлёт SIGTERM
     * (libcore не переопределяет его), поэтому SIGKILL шлём сами через Os.kill. Смерть proot
     * гасит всё дерево: --kill-on-exit (PTRACE_O_EXITKILL) убивает трассируемых потомков.
     */
    private fun killTree(p: Process, graceSec: Long = 3) {
        p.destroy()
        if (p.waitFor(graceSec, TimeUnit.SECONDS)) return
        val pid = pidOf(p)
        if (pid > 0) {
            // Сначала потомки, потом сам proot: на эмуляторе после SIGKILL одного proot часть
            // трассируемых процессов продолжала жить без трассировщика (syscalls -> ENOSYS).
            val kids = ProcTree.descendants(pid)
            (kids + pid).forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
        } else p.destroyForcibly()
        if (!p.waitFor(5, TimeUnit.SECONDS)) EmbeddedRuntime.appendLog(this, "install.log", "proot (pid $pid) не завершился после SIGKILL")
    }

    /** ignoreInstallJob: вызов из finally самой установки (job ещё «активен», но уже завершается). */
    private fun stopSelfIfIdle(ignoreInstallJob: Boolean = false) {
        if (process?.isAlive == true || (!ignoreInstallJob && installJob?.isActive == true)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "murf:local-runtime").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWake() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    private fun set(phase: Phase, msg: String) {
        _state.value = State(phase, msg, installOp)
        EmbeddedRuntime.appendLog(this, "runtime-state.log", "$phase: $msg")
        if (phase != Phase.IDLE || process?.isAlive == true || installJob?.isActive == true) {
            goForeground(tr("MURF: локальная среда", "MURF: local environment"), msg)
        }
    }

    private fun goForeground(title: String, text: String) {
        ensureChannel(this)
        val stop = PendingIntent.getService(
            this, 7702, Intent(this, LocalRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PhoneBridgeService.openAppIntent(this, "local"))
            .addAction(0, tr("Остановить", "Stop"), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    /** После ошибки установки: обычное (не FGS) уведомление без «Стоп», смахивается, по нажатию открывает экран. */
    private fun notifyResult(text: String, update: Boolean = false) {
        runCatching {
            ensureChannel(this)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(if (update) tr("MURF: обновление не завершено", "MURF: update failed") else tr("MURF: установка не завершена", "MURF: installation failed"))
                .setContentText(if (update) tr("Откройте «Среда на телефоне» и нажмите «Обновить» для повтора", "Open “Phone environment” and tap “Update” to retry")
                    else tr("Откройте «Среда на телефоне» и нажмите «Установить» для повтора", "Open “Phone environment” and tap “Install” to retry"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PhoneBridgeService.openAppIntent(this, "local"))
                .build()
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, n)
        }
    }

    override fun onDestroy() {
        // служба уничтожена (стоп пользователем или системой): процессы proot не оставляем сиротами
        process?.let { p -> runCatching { runBlocking { EmbeddedRuntime.murfLocal(this@LocalRuntimeService, listOf("stop"), 20_000) } }; killTree(p, graceSec = 3) }
        process = null
        installProcess?.let { killTree(it, graceSec = 1) }; installProcess = null
        releaseWake()
        val st = _state.value
        if (st.phase == Phase.STOPPING) _state.value = State(Phase.IDLE, tr("Остановлено", "Stopped"), st.op)
        else if (st.phase != Phase.ERROR) _state.value = State(Phase.IDLE, st.message, st.op)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_INSTALL = "com.uzairansar.hermex.local.INSTALL"
        const val ACTION_START = "com.uzairansar.hermex.local.START"
        const val ACTION_STOP = "com.uzairansar.hermex.local.STOP"
        const val CHANNEL = "murf_local_runtime"
        const val NOTIFICATION_ID = 7701
        const val OP_INSTALL = "install"
        const val OP_UPDATE = "update"

        @Volatile private var process: Process? = null
        @Volatile private var installProcess: Process? = null
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        val running: Boolean get() = process?.isAlive == true
        val installing: Boolean get() = _state.value.phase == Phase.INSTALLING

        fun send(c: Context, action: String) {
            ContextCompat.startForegroundService(c, Intent(c, LocalRuntimeService::class.java).setAction(action))
        }

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Локальная среда MURF", "MURF local environment"), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

/** pid дочернего процесса: java.lang.Process.pid() на Android недоступен, у UNIXProcess есть поле pid. */
internal fun pidOf(p: Process): Int {
    var c: Class<*>? = p.javaClass
    while (c != null) {
        val k = c
        val v = runCatching { k.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
        if (v != null && v > 0) return v
        c = c.superclass
    }
    return Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
}
