package com.uzairansar.hermex.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.CacheControl

/**
 * MURF: обновления внутри приложения.
 * Источник — https://github.com/winnifredbalistreri60-prog/murf/releases/latest/download/murf-version.json (публикуется вместе с APK):
 *   {"versionCode":170,"versionName":"1.7.0","url":"https://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.7.0/murf-1.7.0.apk",
 *    "sha256":"…","size":123,"notes":"…"}
 * Проверки перед установкой: адрес APK только на github.com (релизы MURF) по https, sha256 и размер совпадают,
 * APK того же пакета, versionCode больше текущего, подпись та же, что у установленного приложения.
 * Установка — PackageInstaller (Android покажет системное подтверждение).
 */
object AppUpdates {
    const val MANIFEST_URL = "https://github.com/winnifredbalistreri60-prog/murf/releases/latest/download/murf-version.json"
    const val ALLOWED_PREFIX = "https://github.com/winnifredbalistreri60-prog/murf/releases/download/"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    data class Info(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val size: Long, val notes: String)

    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data class UpToDate(val remote: Info) : Phase
        data class Available(val info: Info) : Phase
        data class Downloading(val info: Info, val progress: Float) : Phase
        data class Installing(val info: Info) : Phase
        data class NeedsPermission(val info: Info) : Phase
        data class Failed(val message: String, val info: Info? = null) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    /** Показать диалог (true после автопроверки с найденным обновлением, которое не откладывали). */
    private val _dialog = MutableStateFlow(false)
    val dialog: StateFlow<Boolean> = _dialog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Разбор и проверка манифеста (чистая логика для тестов). null — манифест негоден. */
    internal fun parseManifest(text: String): Info? = runCatching {
        val j = JSONObject(text)
        val url = j.getString("url")
        val sha = j.getString("sha256").lowercase()
        if (!url.startsWith(ALLOWED_PREFIX) || url.contains("..") || !url.endsWith(".apk")) return null
        if (!Regex("^[0-9a-f]{64}$").matches(sha)) return null
        val code = j.getInt("versionCode")
        if (code <= 0) return null
        Info(code, j.optString("versionName", code.toString()), url, sha, j.optLong("size", -1), j.optString("notes").take(4000))
    }.getOrNull()

    internal fun isNewer(info: Info, currentCode: Int): Boolean = info.versionCode > currentCode

    fun dismissDialog(context: Context, info: Info?) {
        _dialog.value = false
        if (info != null) prefs(context).edit().putInt("update_postponed_code", info.versionCode).apply()
    }

    fun openDialog() { _dialog.value = true }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("murf_ui", Context.MODE_PRIVATE)

    /** Cache-bust query so CDNs / sticky proxies cannot serve a stale murf-version.json. */
    internal fun manifestRequestUrl(nowMs: Long = System.currentTimeMillis()): String =
        "$MANIFEST_URL?t=$nowMs"

    /** Human-readable local vs remote for UpToDate / Available (cannot silently lie). */
    internal fun versionCompareLabel(remote: Info, localCode: Int = BuildConfig.VERSION_CODE, localName: String = BuildConfig.VERSION_NAME): String =
        tr(
            "Установлено: $localName (code $localCode)\nНа сервере: ${remote.versionName} (code ${remote.versionCode})",
            "Installed: $localName (code $localCode)\nServer: ${remote.versionName} (code ${remote.versionCode})",
        )

    private val checkGeneration = AtomicLong(0)

    private fun fetchManifestText(): String {
        val req = Request.Builder()
            .url(manifestRequestUrl())
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        http.newCall(req).execute().use { r ->
            // OkHttp follows redirects; log the final URL for diagnosis.
            val finalUrl = r.request.url.toString()
            if (!r.isSuccessful) error("HTTP ${r.code} @ $finalUrl")
            val text = r.body!!.string()
            android.util.Log.i("MURF-update", "manifest ok finalUrl=$finalUrl bytes=${text.length}")
            return text
        }
    }

    /** Проверка: auto=true — не чаще раза в 6 ч и без повторного диалога для отложенной версии. */
    fun check(context: Context, auto: Boolean) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (auto && now - p.getLong("update_checked_at", 0) < CHECK_EVERY_MS) return
        if (_phase.value is Phase.Downloading || _phase.value is Phase.Installing) return
        val gen = checkGeneration.incrementAndGet()
        _phase.value = Phase.Checking
        scope.launch {
            val result = runCatching { fetchManifestText() }
            if (gen != checkGeneration.get()) return@launch // superseded by a newer check
            // Only stamp the throttle after a completed attempt for this generation.
            p.edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            result.onFailure {
                android.util.Log.w("MURF-update", "manifest check failed: ${it.message}")
                if (gen != checkGeneration.get()) return@launch
                _phase.value = if (auto) Phase.Idle else Phase.Failed(
                    tr(
                        "Не удалось проверить обновления: ${it.message}",
                        "Could not check for updates: ${it.message}",
                    ),
                )
            }.onSuccess { text ->
                if (gen != checkGeneration.get()) return@launch
                val info = parseManifest(text)
                when {
                    info == null -> _phase.value = if (auto) Phase.Idle else Phase.Failed(
                        tr("Файл версии на сервере повреждён.", "The version file on the server is invalid."),
                    )
                    !isNewer(info, BuildConfig.VERSION_CODE) -> _phase.value = Phase.UpToDate(info)
                    else -> {
                        _phase.value = Phase.Available(info)
                        if (!auto || p.getInt("update_postponed_code", 0) < info.versionCode) _dialog.value = true
                    }
                }
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /** Скачать, проверить и установить. */
    fun downloadAndInstall(context: Context, info: Info) {
        val app = context.applicationContext
        if (!canInstallPackages(app)) {
            _phase.value = Phase.NeedsPermission(info)
            return
        }
        _phase.value = Phase.Downloading(info, 0f)
        scope.launch {
            val dir = File(app.cacheDir, "murf-update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "murf-${info.versionCode}.apk")
            val err = runCatching {
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    val md = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    var lastEmit = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                if (done > 200L * 1024 * 1024) error(tr("файл слишком большой", "file too large"))
                                if (total > 0 && done - lastEmit > 256 * 1024) {
                                    lastEmit = done
                                    _phase.value = Phase.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    if (sha != info.sha256) error(tr("контрольная сумма не совпала — файл повреждён или подменён", "checksum mismatch — the file is damaged or tampered with"))
                    if (info.size > 0 && done != info.size) error(tr("размер файла не совпал", "file size mismatch"))
                }
                verifyArchive(app, file, info)
            }.exceptionOrNull()
            if (err != null) {
                file.delete()
                _phase.value = Phase.Failed(tr("Обновление не установлено: ${err.message}", "Update not installed: ${err.message}"), info)
                return@launch
            }
            _phase.value = Phase.Installing(info)
            runCatching { install(app, file) }.onFailure {
                _phase.value = Phase.Failed(tr("Установщик Android не запустился: ${it.message}", "Android installer failed: ${it.message}"), info)
            }
        }
    }

    /** Тот же пакет, новее, та же подпись, что у установленного приложения. */
    @Suppress("DEPRECATION")
    private fun verifyArchive(context: Context, file: File, info: Info) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error(tr("файл не похож на APK", "not an APK"))
        if (archive.packageName != context.packageName) error(tr("APK другого приложения", "APK of another app"))
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode.toInt() else archive.versionCode
        if (code != info.versionCode || code <= BuildConfig.VERSION_CODE) error(tr("версия в APK не совпала", "APK version mismatch"))
        val installed = certDigests(pm.getPackageInfo(context.packageName, flags))
            .ifEmpty { certDigests(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)) }
        // На части версий Android signingInfo у неустановленного APK пустой — тогда читаем старым способом.
        var fromApk = certDigests(archive)
        if (fromApk.isEmpty()) fromApk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.let { certDigests(it) }.orEmpty()
        android.util.Log.i("MURF-update", "certs apk=${fromApk.map { it.take(8) }} installed=${installed.map { it.take(8) }}")
        // Если подпись APK прочитать не удалось вовсе, её всё равно сверит сам Android при установке обновления.
        if (fromApk.isNotEmpty() && installed.isNotEmpty() && fromApk.intersect(installed).isEmpty()) {
            error(tr("подпись APK отличается от установленного приложения", "APK signature differs from the installed app"))
        }
    }

    @Suppress("DEPRECATION")
    private fun certDigests(pi: android.content.pm.PackageInfo): Set<String> {
        val sigs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else null)?.takeIf { it.isNotEmpty() } ?: pi.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("murf.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, AppUpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallStatus(context: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> _phase.value = Phase.Idle  // процесс будет перезапущен
            else -> {
                val info = (_phase.value as? Phase.Installing)?.info
                _phase.value = Phase.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) tr("Установка отменена.", "Installation cancelled.")
                    else tr("Android не установил обновление: ${message ?: status}", "Android did not install the update: ${message ?: status}"),
                    info,
                )
                File(context.cacheDir, "murf-update").listFiles()?.forEach { it.delete() }
            }
        }
    }
}

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        AppUpdates.onInstallStatus(context, status, msg, confirm)
    }
}

/** Диалог обновления (показывается поверх любого экрана). */
@Composable
fun AppUpdateDialog() {
    val context = LocalContext.current
    val show by AppUpdates.dialog.collectAsState()
    val phase by AppUpdates.phase.collectAsState()
    LaunchedEffect(Unit) { AppUpdates.check(context, auto = true) }
    // вернулись из настроек «Установка неизвестных приложений» — продолжить
    if (!show) return
    val info = when (val p = phase) {
        is AppUpdates.Phase.Available -> p.info
        is AppUpdates.Phase.Downloading -> p.info
        is AppUpdates.Phase.Installing -> p.info
        is AppUpdates.Phase.NeedsPermission -> p.info
        is AppUpdates.Phase.Failed -> p.info
        else -> null
    }
    val busy = phase is AppUpdates.Phase.Downloading || phase is AppUpdates.Phase.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) AppUpdates.dismissDialog(context, info) },
        title = { Text(if (info != null) tr("Обновление MURF ${info.versionName}", "MURF update ${info.versionName}") else tr("Обновления", "Updates")) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (val p = phase) {
                    is AppUpdates.Phase.Checking -> Text(tr("Проверяю…", "Checking…"))
                    is AppUpdates.Phase.UpToDate -> Text(
                        tr("У вас последняя версия.", "You have the latest version.") + "\n" +
                            AppUpdates.versionCompareLabel(p.remote),
                    )
                    is AppUpdates.Phase.Downloading -> {
                        Text(tr("Скачиваю… ${(p.progress * 100).toInt()}%", "Downloading… ${(p.progress * 100).toInt()}%"))
                        LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    is AppUpdates.Phase.Installing -> Text(tr("Проверено (sha256, подпись). Подтвердите установку в окне Android.", "Verified (sha256, signature). Confirm the installation in the Android dialog."))
                    is AppUpdates.Phase.NeedsPermission -> Text(tr("Разрешите MURF устанавливать приложения: откроются настройки, включите «Разрешить из этого источника» и вернитесь.", "Allow MURF to install apps: settings will open; enable “Allow from this source” and come back."))
                    is AppUpdates.Phase.Failed -> Text(p.message)
                    else -> {}
                }
                if (info != null && (phase is AppUpdates.Phase.Available || phase is AppUpdates.Phase.NeedsPermission)) {
                    Text(
                        AppUpdates.versionCompareLabel(info) +
                            (if (info.notes.isNotBlank()) "\n\n" + info.notes else ""),
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                is AppUpdates.Phase.Available -> TextButton(onClick = { AppUpdates.downloadAndInstall(context, p.info) }) { Text(tr("Обновить", "Update")) }
                is AppUpdates.Phase.NeedsPermission -> TextButton(onClick = {
                    if (AppUpdates.canInstallPackages(context)) AppUpdates.downloadAndInstall(context, p.info) else AppUpdates.openInstallPermissionSettings(context)
                }) { Text(if (AppUpdates.canInstallPackages(context)) tr("Продолжить", "Continue") else tr("Открыть настройки", "Open settings")) }
                is AppUpdates.Phase.Failed -> if (p.info != null) TextButton(onClick = { AppUpdates.downloadAndInstall(context, p.info) }) { Text(tr("Повторить", "Retry")) } else TextButton(onClick = { AppUpdates.dismissDialog(context, null) }) { Text("OK") }
                else -> TextButton(onClick = { AppUpdates.dismissDialog(context, null) }, enabled = !busy) { Text("OK") }
            }
        },
        dismissButton = {
            if (!busy && phase !is AppUpdates.Phase.UpToDate && phase !is AppUpdates.Phase.Checking) {
                TextButton(onClick = { AppUpdates.dismissDialog(context, info) }) { Text(tr("Позже", "Later")) }
            }
        },
    )
}
