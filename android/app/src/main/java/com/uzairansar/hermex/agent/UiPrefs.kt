package com.uzairansar.hermex.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Название приложения (видимое пользователю). applicationId остаётся ru.dredd20.agent. */
const val APP_DISPLAY_NAME = "MURF"

/**
 * Настройки интерфейса MURF: масштаб шрифта и свайпы. Хранятся в SharedPreferences «murf_ui»,
 * читаются синхронно при старте (HermexApplication.onCreate), поэтому шрифт верный с первого кадра.
 */
object UiPrefs {
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.5f
    val presets get() = listOf(tr("Мелкий", "Small") to 0.9f, tr("Обычный", "Normal") to 1.0f, tr("Крупный", "Large") to 1.2f, tr("Очень крупный", "Extra large") to 1.4f)

    private var sp: SharedPreferences? = null
    private val _fontScale = MutableStateFlow(1f)
    private val _swipes = MutableStateFlow(true)

    /** Режим живого экрана: false — «Смотреть» (по умолчанию при каждом запуске), true — «Управлять». Не сохраняется. */
    private val _screenControl = MutableStateFlow(false)
    val screenControl: StateFlow<Boolean> = _screenControl
    fun setScreenControl(v: Boolean) { _screenControl.value = v }
    val fontScale: StateFlow<Float> = _fontScale
    val swipesEnabled: StateFlow<Boolean> = _swipes

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences("murf_ui", Context.MODE_PRIVATE)
        sp = prefs
        _fontScale.value = clamp(prefs.getFloat("font_scale", 1f))
        _swipes.value = prefs.getBoolean("swipes", true)
    }

    fun clamp(v: Float): Float = ((v.coerceIn(MIN_SCALE, MAX_SCALE) * 20f).roundToInt() / 20f)

    fun setFontScale(v: Float) {
        val c = clamp(v)
        _fontScale.value = c
        sp?.edit()?.putFloat("font_scale", c)?.apply()
    }

    fun setSwipesEnabled(v: Boolean) {
        _swipes.value = v
        sp?.edit()?.putBoolean("swipes", v)?.apply()
    }
}

/** Пользовательский множитель шрифта (для View-элементов вроде Markwon TextView, где LocalDensity не действует). */
val LocalUserFontScale = compositionLocalOf { 1f }

/** Применяет масштаб шрифта ко всему содержимому: Compose sp идут через LocalDensity.fontScale. */
@Composable
fun ProvideUserFontScale(content: @Composable () -> Unit) {
    val scale by UiPrefs.fontScale.collectAsState()
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * scale),
        LocalUserFontScale provides scale,
        content = content,
    )
}

// ---------------------------------------------------------------- свайпы

/** Общее состояние текущего касания: начато ли оно в «прозрачном» AndroidView (Markwon, noVNC view-only). */
internal object SwipeGuard {
    @Volatile var interopOk = false
    @Volatile var blocked = false
}

/**
 * Помечает область, над которой свайпы приложения запрещены (живой экран в режиме «Управлять»).
 * Нужен явный флаг: AndroidView получает касания в проходе Final уже после родителя,
 * поэтому «потребление» событий WebView наблюдатель свайпа увидеть не успевает.
 */
fun Modifier.swipeBlockingInterop(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        SwipeGuard.blocked = true
    }
}

/**
 * Помечает AndroidView, которому горизонтальный жест не нужен (Markwon TextView, noVNC в режиме просмотра).
 * Такие View «съедают» все события касания, и без пометки свайп поверх них считался бы конфликтом.
 */
fun Modifier.swipeTransparentInterop(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        SwipeGuard.interopOk = true
    }
}

/**
 * Горизонтальный свайп-навигатор. Ничего не перехватывает и не потребляет — только наблюдает (проход Final)
 * и срабатывает при отпускании пальца, если:
 *  - касание началось вне системных зон жестов (WindowInsets.systemGestures слева/справа);
 *  - |dx| ≥ 72dp, |dx| ≥ 1.8·|dy|, жест короче 1,5 с, один палец;
 *  - палец начал двигаться до срабатывания long-press (системный таймаут) (иначе это долгое нажатие — выделение текста);
 *  - движение не забрал дочерний элемент (горизонтальная прокрутка кода/чипов, вертикальный список,
 *    выделение текста, перетаскивание) — кроме помеченных swipeTransparentInterop() AndroidView.
 * Свайп вправо засчитывается, только если начат левее rightStartMaxFraction ширины.
 */
fun Modifier.horizontalSwipeNav(
    enabled: Boolean,
    onSwipeLeft: (() -> Unit)?,
    onSwipeRight: (() -> Unit)?,
    rightStartMaxFraction: Float = 1f,
): Modifier = composed {
    if (!enabled || (onSwipeLeft == null && onSwipeRight == null)) return@composed Modifier
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val left by rememberUpdatedState(onSwipeLeft)
    val right by rememberUpdatedState(onSwipeRight)
    pointerInput(rightStartMaxFraction) {
        val minDx = 72.dp.toPx()
        val slop = viewConfiguration.touchSlop
        // Движение, начатое после long-press, — это выделение текста/перетаскивание, а не свайп.
        val longPress = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            SwipeGuard.interopOk = false
            SwipeGuard.blocked = false
            val w = size.width.toFloat()
            val edgeL = gestureInsets.getLeft(density, layoutDirection).toFloat()
            val edgeR = gestureInsets.getRight(density, layoutDirection).toFloat()
            val start = down.position
            if (start.x <= edgeL || start.x >= w - edgeR) return@awaitEachGesture
            val t0 = down.uptimeMillis
            var aborted = false
            var moving = false
            var why = ""
            var firstMove = -1L
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Final)
                if (ev.changes.count { it.pressed } > 1) { aborted = true; why = "multi" }
                if (SwipeGuard.blocked) { aborted = true; why = "blocked-area" }
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                val d = c.position - start
                if (!moving && d.getDistance() > slop) {
                    moving = true
                    firstMove = c.uptimeMillis - t0
                    if (c.uptimeMillis - t0 > longPress) { aborted = true; why = "late" }
                }
                if (c.pressed && c.position != c.previousPosition && c.isConsumed && !SwipeGuard.interopOk) { aborted = true; why = "consumed" }
                if (!c.pressed) {
                    val ok = !aborted && c.uptimeMillis - t0 < 1500 &&
                        abs(d.x) >= minDx && abs(d.x) >= 1.8f * abs(d.y)
                    android.util.Log.d("MURFSwipe", "up ok=$ok aborted=$aborted dt=${c.uptimeMillis - t0} dx=${d.x} dy=${d.y} why=$why firstMove=$firstMove")
                    if (ok) {
                        if (d.x < 0) left?.invoke()
                        else if (start.x <= w * rightStartMaxFraction) right?.invoke()
                    }
                    break
                }
            }
        }
    }
}

// ---------------------------------------------------------------- размер шрифта (UI)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FontSizeControls(modifier: Modifier = Modifier) {
    val saved by UiPrefs.fontScale.collectAsState()
    var live by remember(saved) { mutableFloatStateOf(saved) }
    Column(modifier.fillMaxWidth().testTag("font_size_controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Размер шрифта", "Font size"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(live * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = live,
            onValueChange = { live = UiPrefs.clamp(it) },
            onValueChangeFinished = { UiPrefs.setFontScale(live) },
            valueRange = UiPrefs.MIN_SCALE..UiPrefs.MAX_SCALE,
            steps = 12,
            modifier = Modifier.testTag("font_size_slider"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UiPrefs.presets.forEach { (label, value) ->
                FilterChip(
                    selected = abs(live - value) < 0.01f,
                    onClick = { live = value; UiPrefs.setFontScale(value) },
                    label = { Text(label) },
                )
            }
        }
        // Живой предпросмотр: масштаб применяется к образцу сразу, ко всему приложению — по отпусканию ползунка.
        val base = LocalDensity.current
        val ratio = if (saved > 0f) live / saved else 1f
        CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * ratio)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(12.dp)
                    .testTag("font_size_preview"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("Пример сообщения", "Sample message"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Так будет выглядеть текст в чате, меню и настройках $APP_DISPLAY_NAME.", "This is how text will look in chat, menus and settings of $APP_DISPLAY_NAME."), style = MaterialTheme.typography.bodyMedium)
                Text(tr("print(\"привет\")  # код", "print(\"hello\")  # code"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FontSizeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Размер шрифта", "Font size")) },
        text = { FontSizeControls() },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Готово", "Done")) } },
        dismissButton = { TextButton(onClick = { UiPrefs.setFontScale(1f) }) { Text(tr("Сбросить", "Reset")) } },
    )
}


// ---------------------------------------------------------------- экран агента: Смотреть / Управлять

/** Переключатель «Смотреть / Управлять» для живого экрана (вкладка «Экран» и оверлей в чате). */
@Composable
fun ScreenModeToggle(control: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF1C1C21))
            .padding(3.dp)
            .testTag("screen_mode_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false to tr("👁 Смотреть", "👁 View"), true to tr("🖱 Управлять", "🖱 Control")).forEach { (value, label) ->
            val selected = control == value
            Text(
                label,
                color = if (selected) androidx.compose.ui.graphics.Color(0xFF111111) else androidx.compose.ui.graphics.Color(0xFFDDDDE2),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (selected) androidx.compose.ui.graphics.Color(0xFFF5C400) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { if (!selected) onChange(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(if (value) "screen_mode_control" else "screen_mode_view"),
            )
        }
    }
}

/** Подтверждение перед включением управления, пока агент работает на экране. */
@Composable
fun ScreenControlConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Агент сейчас работает", "The agent is working now")) },
        text = { Text(tr("Ваши клики и ввод попадут на тот же экран и могут помешать агенту. Включить управление?", "Your clicks and typing go to the same screen and may disturb the agent. Enable control?")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Управлять", "Control")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
