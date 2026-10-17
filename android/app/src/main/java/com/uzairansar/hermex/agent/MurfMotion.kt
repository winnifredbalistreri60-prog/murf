package com.uzairansar.hermex.agent

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.delay

/**
 * MURF: анимации интерфейса. Спокойные и короткие (150–260 мс), без «прыжков».
 * Выключаются, если в Настройках MURF «Анимации» выкл. или в системе Android
 * «Удалить анимацию» / масштаб анимации 0 (специальные возможности).
 */
object MurfMotion {
    /** Системный запрет анимаций: масштаб длительности аниматора 0 (Настройки → Спец. возможности → Удалить анимацию). */
    fun systemReduced(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Чистая логика для тестов. */
    fun reduceMotion(userEnabled: Boolean, systemReduced: Boolean): Boolean = !userEnabled || systemReduced

    // Навигация между экранами: лёгкий сдвиг на 1/10 ширины + затухание.
    private const val NavMs = 240

    fun enter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduce) EnterTransition.None
        else fadeIn(tween(NavMs, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { it / 10 }
    }

    fun exit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduce) ExitTransition.None else fadeOut(tween(160))
    }

    fun popEnter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduce) EnterTransition.None
        else fadeIn(tween(NavMs, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { -it / 10 }
    }

    fun popExit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduce) ExitTransition.None
        else fadeOut(tween(160)) + slideOutHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { it / 10 }
    }

    // Элементы списка чата: появление/удаление/перестановка.
    fun itemFadeIn(reduce: Boolean): FiniteAnimationSpec<Float>? = if (reduce) null else tween(220, easing = FastOutSlowInEasing)
    fun itemFadeOut(reduce: Boolean): FiniteAnimationSpec<Float>? = if (reduce) null else tween(140)
    fun itemPlacement(reduce: Boolean): FiniteAnimationSpec<IntOffset>? =
        if (reduce) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
}

/** true — анимации нужно отключить (настройка MURF или системная). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val enabled by UiPrefs.animations.collectAsState()
    val system = remember(context) { MurfMotion.systemReduced(context) }
    return MurfMotion.reduceMotion(enabled, system)
}

/** Время работы агента «0:42» / «1:02:05». */
internal fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Три точки «волной»: индикатор того, что агент готовит ответ. При reduce-motion — неподвижные. */
@Composable
fun MurfTypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) {
    val reduce = rememberReduceMotion()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (reduce) {
            repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = 0.6f))) }
        } else {
            val t = rememberInfiniteTransition(label = "murf-dots")
            repeat(3) { i ->
                val phase by t.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1100, delayMillis = 0, easing = FastOutSlowInEasing), RepeatMode.Restart),
                    label = "dot$i",
                )
                // сдвиг фазы на точку, «волна» по синусу
                val p = ((phase - i * 0.16f) % 1f + 1f) % 1f
                val k = if (p < 0.5f) p * 2f else (1f - p) * 2f
                Box(
                    Modifier.size(7.dp)
                        .graphicsLayer { translationY = -k * 5.dp.toPx(); alpha = 0.35f + 0.6f * k }
                        .clip(CircleShape).background(color),
                )
            }
        }
    }
}

/**
 * Полоска «агент работает» над полем ввода: бегущий блик, время работы, текущий шаг.
 * Видна только пока идёт ответ. При reduce-motion — статичная полоска.
 */
@Composable
fun AgentWorkingStrip(startedAtMs: Long?, activity: String?, queued: Int, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val accent = MaterialTheme.colorScheme.primary
    val label = buildString {
        append(tr("Агент работает", "Agent is working"))
        if (startedAtMs != null) append(" · ").append(formatElapsed(now - startedAtMs))
        activity?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it.lineSequence().first().take(60)) }
        if (queued > 0) append(" · ").append(tr("в очереди: $queued", "queued: $queued"))
    }
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp).semantics { contentDescription = label },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MurfTypingDots(color = accent)
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.padding(top = 5.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))
                .background(accent.copy(alpha = 0.15f)),
        ) {
            if (!reduce) {
                val t = rememberInfiniteTransition(label = "murf-strip")
                val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "x")
                Box(
                    Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = 0.9f }
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                (x - 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                                x.coerceIn(0.001f, 0.999f) to accent,
                                (x + 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                        ),
                )
            }
        }
    }
}
