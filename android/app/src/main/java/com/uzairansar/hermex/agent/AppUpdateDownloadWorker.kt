package com.uzairansar.hermex.agent

import com.uzairansar.hermex.ui.localization.localizedString
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.uzairansar.hermex.R
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Скачивание APK-обновления в WorkManager — переживает смерть процесса (раньше загрузка
 * жила в корутине AppUpdates и обрывалась вместе с процессом).
 *
 * Вся тяжёлая логика осталась в [AppUpdates]: OkHttp-стриминг с SHA-256, прогресс по 256КБ,
 * лимит 200МБ, сверка контрольной суммы и размера, verifyArchive и install (файл после
 * установки не удаляется). Воркер отвечает за фазы (UI читает их через AppUpdates.phase),
 * foreground-уведомление и retry-политику: IOException → Result.retry() с экспоненциальным
 * backoff от 30с, максимум [MAX_ATTEMPTS] попыток; прочие ошибки → Result.failure().
 *
 * Expedited-режим намеренно не используется: квота expedited не рассчитана на долгие
 * загрузки, обычный воркер с setForeground(FGS type=dataSync) работает без этого ограничения.
 * На API 33+ без разрешения POST_NOTIFICATIONS работа продолжается без foreground-уведомления.
 */
class AppUpdateDownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val info = readInfo() ?: run {
            AppUpdates.setFailed(
                applicationContext.localizedString("Update not installed: work input is invalid"),
                null,
            )
            return Result.failure()
        }
        AppUpdates.setDownloadingProgress(info, 0f)
        val foregroundOk = canPostNotifications()
        var lastDecile = -1
        if (foregroundOk) {
            runCatching { setForeground(foregroundInfo(info, 0f)) }
                .onFailure { Log.w(TAG, "setForeground failed, continuing without notification: ${it.message}") }
        }
        return try {
            val file = AppUpdates.downloadVerifiedApk(applicationContext, info) { pct ->
                AppUpdates.setDownloadingProgress(info, pct)
                if (foregroundOk) {
                    // Обновляем уведомление не чаще раза на 10% прогресса.
                    val decile = ((pct * 10f).toInt()) * 10
                    if (decile > lastDecile) {
                        lastDecile = decile
                        runCatching {
                            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, buildNotification(info, pct))
                        }
                    }
                }
            }
            AppUpdates.setInstalling(info)
            runCatching { AppUpdates.installApk(applicationContext, file) }
                .onFailure {
                    AppUpdates.setFailed(
                        applicationContext.localizedStringFormat("Android installer failed: %1\$s", it.message),
                        info,
                    )
                }
                .fold({ Result.success() }, { Result.failure() })
        } catch (e: CancellationException) {
            throw e // остановка воркера — не превращаем в failure
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Log.w(TAG, "download attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS failed: ${e.message}")
                AppUpdates.setDownloadingProgress(info, 0f)
                Result.retry()
            } else {
                AppUpdates.setFailed(applicationContext.localizedStringFormat("Update not installed: %1\$s", e.message), info)
                Result.failure()
            }
        } catch (e: Exception) {
            // Контрольная сумма, размер, verifyArchive и т.п. — ретраи не помогут.
            AppUpdates.setFailed(applicationContext.localizedStringFormat("Update not installed: %1\$s", e.message), info)
            Result.failure()
        }
    }

    /** Входные данные — короткие строки (Data ограничена 10КБ). */
    private fun readInfo(): AppUpdates.Info? {
        val d = inputData
        val url = d.getString(KEY_URL) ?: return null
        val sha = d.getString(KEY_SHA256) ?: return null
        val code = d.getInt(KEY_VERSION_CODE, -1)
        if (url.isBlank() || sha.isBlank() || code <= 0) return null
        return AppUpdates.Info(
            versionCode = code,
            versionName = d.getString(KEY_VERSION_NAME)?.takeIf { it.isNotBlank() } ?: code.toString(),
            url = url,
            sha256 = sha,
            size = d.getLong(KEY_SIZE, -1),
            notes = d.getString(KEY_NOTES).orEmpty().take(4000),
        )
    }

    /** API 33+: без POST_NOTIFICATIONS работаем без foreground-уведомления. */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun foregroundInfo(info: AppUpdates.Info, progress: Float): ForegroundInfo {
        val notification = buildNotification(info, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(info: AppUpdates.Info, progress: Float): Notification {
        val context = applicationContext
        ensureChannel(context)
        val pct = (progress * 100).coerceIn(0f, 100f)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hermex_launcher_monochrome)
            .setContentTitle(context.localizedStringFormat("MURF update %1\$s", info.versionName))
            .setContentText(context.localizedStringFormat("Downloading… %1\$d%%", pct.toInt()))
            .setProgress(100, pct.toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /** Канал уведомлений по образцу StreamStatusNotifier (идемпотентно, один раз). */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.localizedString("Update download"), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.localizedString("MURF update download progress")
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "MURF-update"
        private const val CHANNEL_ID = "murf_update_download"
        private const val NOTIFICATION_ID = 0x4D555246

        /** Всего попыток: IOException → Result.retry(), дальше — Result.failure(). */
        const val MAX_ATTEMPTS = 3
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE = "size"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_VERSION_NAME = "versionName"
        const val KEY_NOTES = "notes"
    }
}

private fun android.content.Context.localizedStringFormat(template: String, vararg args: Any?): String =
    String.format(localizedString(template), *args)
