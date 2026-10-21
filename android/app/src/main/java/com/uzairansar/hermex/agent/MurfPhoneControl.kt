package com.uzairansar.hermex.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Управление экраном телефона для локального агента (DeepSeek Harness → MCP murf_phone → мост MURF).
 * Включается только вручную: Настройки Android → Спец. возможности → MURF. Каждое нажатие/ввод в другом
 * приложении подтверждается в MURF. «Стоп» (уведомление/чат) сразу запрещает все действия на экране.
 */
class MurfAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: MurfAccessibilityService? = null
        /** «Взять экран»: пока true, агент не может нажимать/вводить/свайпать. */
        @Volatile var userHasControl = false

        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(c, MurfAccessibilityService::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }
        fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Последние уведомления (только если пользователь дал MURF «Доступ к уведомлениям»). */
class MurfNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString().orEmpty()
        synchronized(recent) {
            recent.addFirst(Note(sbn.postTime, sbn.packageName, title.take(200), text.take(500)))
            while (recent.size > 50) recent.removeLast()
        }
    }

    data class Note(val time: Long, val pkg: String, val title: String, val text: String)
    companion object {
        @Volatile var connected = false
        val recent = ArrayDeque<Note>()
        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(c, MurfNotificationListener::class.java).flattenToString())
        }
        fun settingsIntent() = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Реализация инструментов экрана/приложений. Возвращает (ok, текст). */
object PhoneControl {
    private fun svc(): MurfAccessibilityService? = MurfAccessibilityService.instance
    private val noSvc = tr("Нет доступа к экрану: включите MURF в «Настройки → Спец. возможности» (MURF → Телефон → Управление экраном)",
        "No screen access: enable MURF in Settings → Accessibility (MURF → Phone → Screen control)")
    private val userControl = tr("Пользователь взял экран — действия агента на экране остановлены", "The user took the screen — agent screen actions are stopped")

    fun network(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return tr("Нет сети", "No network")
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("мобильная", "cellular")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> tr("другая", "other")
        }
        return tr("Сеть: ", "Network: ") + type +
            tr(", интернет: ", ", internet: ") + (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) tr("да", "yes") else tr("нет", "no")) +
            ", VPN: " + (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) tr("да", "yes") else tr("нет", "no")) +
            tr(", лимитная: ", ", metered: ") + (if (cm.isActiveNetworkMetered) tr("да", "yes") else tr("нет", "no"))
    }

    fun listApps(c: Context, query: String?): String {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
            .filter { q -> query.isNullOrBlank() || q.first.contains(query, true) || q.second.contains(query, true) }
            .sortedBy { it.first.lowercase() }
        return apps.take(300).joinToString("\n") { "${it.first} — ${it.second}" }.ifBlank { tr("Ничего не найдено", "Nothing found") }
    }

    fun openApp(c: Context, pkgOrLabel: String): Pair<Boolean, String> {
        val pm = c.packageManager
        val pkg = if (pm.getLaunchIntentForPackage(pkgOrLabel) != null) pkgOrLabel else
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .firstOrNull { it.loadLabel(pm).toString().equals(pkgOrLabel, true) }?.activityInfo?.packageName
        val i = pkg?.let { pm.getLaunchIntentForPackage(it) } ?: return false to tr("Приложение не найдено: ", "App not found: ") + pkgOrLabel
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .fold({ true to tr("Открыто: ", "Opened: ") + pkg }, { false to (it.message ?: "error") })
    }

    fun openUrl(c: Context, url: String): Pair<Boolean, String> {
        val u = Uri.parse(url)
        if (u.scheme !in setOf("http", "https", "geo", "tel", "mailto", "market")) return false to tr("Схема не разрешена", "Scheme not allowed")
        return runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .fold({ true to tr("Открыто", "Opened") }, { false to (it.message ?: "error") })
    }

    fun share(c: Context, text: String): Pair<Boolean, String> = runCatching {
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "MURF")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.fold({ true to tr("Открыто окно «Поделиться» — выбор за пользователем", "Share sheet opened — the user chooses") }, { false to (it.message ?: "error") })

    fun notifications(c: Context, limit: Int): Pair<Boolean, String> {
        if (!MurfNotificationListener.enabled(c)) return false to tr("Нет доступа к уведомлениям: MURF → Телефон → «Читать уведомления»", "No notification access: MURF → Phone → Read notifications")
        val f = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)
        val l = synchronized(MurfNotificationListener.recent) { MurfNotificationListener.recent.take(limit.coerceIn(1, 50)) }
        return true to l.joinToString("\n") { "${f.format(Date(it.time))} [${it.pkg}] ${it.title}: ${it.text}" }
            .ifBlank { tr("Новых уведомлений с момента включения нет", "No notifications since access was granted") }
    }

    /** Дерево элементов активного окна: текст, id, кликабельность, координаты центра. */
    fun screenView(max: Int): Pair<Boolean, String> {
        val s = svc() ?: return false to noSvc
        val root = s.rootInActiveWindow ?: return false to tr("Окно недоступно", "No active window")
        val sb = StringBuilder("app: ${root.packageName}\n"); var n = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || n >= max) return
            val text = (node.text ?: node.contentDescription)?.toString()?.replace('\n', ' ')?.take(120)
            val r = Rect(); node.getBoundsInScreen(r)
            if (!text.isNullOrBlank() || node.isClickable || node.isEditable) {
                n++
                sb.append("  ".repeat(depth.coerceAtMost(8))).append("• ")
                    .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(if (node.isClickable) " [нажимается]" else "").append(if (node.isEditable) " [поле]" else "")
                    .append(if (!text.isNullOrBlank()) " «$text»" else "")
                    .append(" @(${r.centerX()},${r.centerY()})\n")
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return true to sb.toString()
    }

    suspend fun screenshot(c: Context): Pair<Boolean, String> {
        val s = svc() ?: return false to noSvc
        if (Build.VERSION.SDK_INT < 30) return false to tr("Снимок экрана — с Android 11", "Screenshots need Android 11+")
        val bmp: Bitmap = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                s.takeScreenshot(Display.DEFAULT_DISPLAY, c.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        cont.resume(Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)); r.hardwareBuffer.close()
                    }
                    override fun onFailure(code: Int) { cont.resume(null) }
                })
            }
        } ?: return false to tr("Снимок не получился", "Screenshot failed")
        val dir = File(c.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val f = File(dir, "screen-${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
        val (_, tree) = screenView(150)
        return true to tr("Снимок сохранён на телефоне: ", "Screenshot saved on phone: ") + f.absolutePath + " (${bmp.width}×${bmp.height})\n" + tree
    }

    private suspend fun gesture(path: Path, ms: Long): Boolean {
        val s = svc() ?: return false
        return withTimeoutOrNull(ms + 5000) {
            suspendCancellableCoroutine { cont ->
                val ok = s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                        override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                    }, null)
                if (!ok && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private fun findByText(text: String): AccessibilityNodeInfo? {
        val root = svc()?.rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }
    }

    suspend fun tap(x: Float?, y: Float?, text: String?): Pair<Boolean, String> {
        if (MurfAccessibilityService.userHasControl) return false to userControl
        svc() ?: return false to noSvc
        if (!text.isNullOrBlank()) {
            val node = findByText(text) ?: return false to tr("Не нашла на экране «$text»", "Not found on screen: “$text”")
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true to tr("Нажато «$text»", "Tapped “$text”")
            val r = Rect(); node.getBoundsInScreen(r)
            return tap(r.centerX().toFloat(), r.centerY().toFloat(), null)
        }
        if (x == null || y == null) return false to tr("Нужны x,y или text", "x,y or text required")
        val ok = gesture(Path().apply { moveTo(x, y) }, 60)
        return ok to if (ok) tr("Нажато в ($x, $y)", "Tapped ($x, $y)") else tr("Жест не выполнен", "Gesture failed")
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Pair<Boolean, String> {
        if (MurfAccessibilityService.userHasControl) return false to userControl
        svc() ?: return false to noSvc
        val ok = gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms.coerceIn(50, 3000))
        return ok to if (ok) tr("Свайп выполнен", "Swiped") else tr("Жест не выполнен", "Gesture failed")
    }

    fun type(text: String): Pair<Boolean, String> {
        if (MurfAccessibilityService.userHasControl) return false to userControl
        val s = svc() ?: return false to noSvc
        val f = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false to tr("Нет поля ввода в фокусе — сначала нажмите на поле", "No focused input — tap a field first")
        val cur = f.text?.toString().orEmpty().takeUnless { f.isShowingHintText }.orEmpty()
        val ok = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
        })
        return ok to if (ok) tr("Введено ${text.length} символов", "Typed ${text.length} characters") else tr("Поле не принимает ввод", "Field rejected input")
    }

    fun key(k: String): Pair<Boolean, String> {
        if (MurfAccessibilityService.userHasControl && k != "home") return false to userControl
        val s = svc() ?: return false to noSvc
        val a = when (k.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return false to tr("Кнопки: back, home, recents, notifications", "Keys: back, home, recents, notifications")
        }
        return s.performGlobalAction(a) to k
    }

    /** Папка MURF в общей памяти (через Termux: ~/storage/shared/Download/MURF). */
    const val MURF_DIR = "\$HOME/storage/shared/Download/MURF"

    /** Путь агента → путь в Termux; относительные — внутри папки MURF. Возвращает (путь, внутри_MURF). */
    fun resolve(path: String): Pair<String, Boolean> {
        val p = path.trim()
        if (p.isEmpty() || (!p.startsWith("/") && !p.startsWith("~"))) {
            val clean = p.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
            return "$MURF_DIR/$clean" to true
        }
        val abs = p.replaceFirst(Regex("^~"), "\$HOME")
        val inside = !abs.contains("/../") && (abs.startsWith("$MURF_DIR/") || abs.startsWith("/storage/emulated/0/Download/MURF/") ||
            abs.startsWith("/sdcard/Download/MURF/"))
        return abs to inside
    }
}
