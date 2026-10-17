package com.uzairansar.hermex.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Аватары ботов MURF: собственные простые «человечки» (круглая голова, тельце, точки-глаза, улыбка),
 * главный отличительный знак — шляпа. Образы оригинальные, без персонажей и иллюстраций из книг.
 * Выбор хранится в поле emoji бота как «k<шляпа>-<цвет>» (синхронизируется через сервер, без новых полей).
 */
enum class MurfHat(val ru: String, val en: String) {
    WideBrim("Путник — широкополая шляпа", "Wanderer — wide-brim hat"),
    Cap("Мастер — кепка", "Maker — cap"),
    Wizard("Звездочёт — колпак", "Stargazer — pointed cap"),
    Beret("Художник — берет", "Artist — beret"),
    Flower("Ромашка — шляпка с цветком", "Daisy — hat with a flower"),
    Helmet("Механик — шлем", "Mechanic — helmet"),
    TopHat("Франт — цилиндр", "Dandy — top hat"),
    Beanie("Лыжник — шапка с помпоном", "Skier — bobble hat"),
    Chef("Поварёнок — колпак повара", "Little chef — chef hat"),
    Boater("Рыбак — соломенная шляпа", "Fisher — straw boater"),
}

object MurfAvatarPalette {
    val colors = listOf(
        0xFF3B82F6, 0xFF10A37F, 0xFF475569, 0xFFD97757, 0xFF8B5CF6,
        0xFFF59E0B, 0xFFEF4444, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16,
    ).map { Color(it) }
    val namesRu = listOf("синий", "зелёный", "графит", "терракота", "фиолетовый", "янтарь", "красный", "бирюза", "розовый", "салатовый")
}

data class MurfAvatarSpec(val hat: MurfHat, val colorIndex: Int) {
    val color: Color get() = MurfAvatarPalette.colors[colorIndex.mod(MurfAvatarPalette.colors.size)]
    fun encode(): String = "k${hat.ordinal}-$colorIndex"

    companion object {
        private val re = Regex("^k(\\d)-(\\d)$")

        /** Персонаж и цвет по умолчанию для бэкенда. */
        val backendDefaults = mapOf(
            "hermes" to MurfAvatarSpec(MurfHat.WideBrim, 0),
            "codex" to MurfAvatarSpec(MurfHat.Cap, 1),
            "grok" to MurfAvatarSpec(MurfHat.Wizard, 2),
            "claude" to MurfAvatarSpec(MurfHat.Beret, 3),
            "gemini" to MurfAvatarSpec(MurfHat.Flower, 4),
            "opencode" to MurfAvatarSpec(MurfHat.Helmet, 5),
        )

        fun decode(value: String?): MurfAvatarSpec? = value?.trim()?.let { re.matchEntire(it) }?.let { m ->
            val h = m.groupValues[1].toInt(); val c = m.groupValues[2].toInt()
            if (h < MurfHat.entries.size && c < MurfAvatarPalette.colors.size) MurfAvatarSpec(MurfHat.entries[h], c) else null
        }

        /** Стабильный выбор по имени (FNV-1a, не зависит от версии JVM). */
        fun byName(name: String): MurfAvatarSpec {
            var h = 0x811c9dc5.toInt()
            name.trim().lowercase().forEach { h = (h xor it.code) * 0x01000193 }
            val u = h.toLong() and 0xffffffffL
            return MurfAvatarSpec(MurfHat.entries[(u % MurfHat.entries.size).toInt()], ((u / 16) % MurfAvatarPalette.colors.size).toInt())
        }

        /** Выбранный → по бэкенду (для бота с обычным эмодзи) → по имени. */
        fun resolve(emoji: String?, backend: String?, name: String): MurfAvatarSpec =
            decode(emoji) ?: backendDefaults[backend] ?: byName(name.ifBlank { backend.orEmpty() })
    }
}

enum class MurfBotStatus { Idle, Working, AwaitingConfirm, Unread, Error }

private val backendBadge = mapOf(
    "hermes" to ("H" to Color(0xFF2563EB)), "codex" to ("X" to Color(0xFF0F766E)), "grok" to ("G" to Color(0xFF111827)),
    "claude" to ("C" to Color(0xFFC2410C)), "gemini" to ("M" to Color(0xFF6D28D9)), "opencode" to ("O" to Color(0xFFB45309)),
)
fun murfBackendColor(backend: String?): Color = backendBadge[backend]?.second ?: Color(0xFF64748B)

private fun Color.shade(k: Float) = Color(red * k, green * k, blue * k, alpha)
private fun Color.tint(k: Float) = Color(red + (1 - red) * k, green + (1 - green) * k, blue + (1 - blue) * k, alpha)

/** Рисует человечка в квадрате size×size (фон рисуется отдельно). blink 0..1 — прикрытие глаз. */
fun DrawScope.drawMurfCharacter(spec: MurfAvatarSpec, blink: Float = 0f) {
    val s = size.minDimension
    val skin = Color(0xFFFFE3C8)
    val ink = Color(0xFF1F2937)
    val hatC = spec.color.shade(0.55f)
    val bodyC = spec.color.tint(0.75f)
    val cx = s / 2
    // тельце
    drawRoundRect(bodyC, Offset(s * 0.27f, s * 0.72f), Size(s * 0.46f, s * 0.36f), CornerRadius(s * 0.2f))
    // голова
    val hy = s * 0.55f; val hr = s * 0.21f
    drawCircle(skin, hr, Offset(cx, hy))
    // глаза
    val eyeH = (s * 0.045f) * (1f - blink.coerceIn(0f, 0.9f))
    listOf(-1f, 1f).forEach { d ->
        drawOval(ink, Offset(cx + d * s * 0.075f - s * 0.022f, hy - s * 0.02f - eyeH / 2), Size(s * 0.044f, eyeH.coerceAtLeast(s * 0.008f)))
    }
    // улыбка и щёчки
    drawArc(ink, 20f, 140f, false, Offset(cx - s * 0.07f, hy + s * 0.0f), Size(s * 0.14f, s * 0.09f),
        style = Stroke(s * 0.022f, cap = StrokeCap.Round))
    listOf(-1f, 1f).forEach { d -> drawCircle(Color(0x55F87171), s * 0.025f, Offset(cx + d * s * 0.13f, hy + s * 0.05f)) }
    // шляпа — главный знак
    val top = hy - hr // верх головы
    when (spec.hat) {
        MurfHat.WideBrim -> {
            drawOval(hatC, Offset(cx - s * 0.36f, top + s * 0.02f), Size(s * 0.72f, s * 0.11f))
            drawRoundRect(hatC, Offset(cx - s * 0.17f, top - s * 0.13f), Size(s * 0.34f, s * 0.2f), CornerRadius(s * 0.08f))
            drawRect(spec.color.tint(0.4f), Offset(cx - s * 0.17f, top + s * 0.0f), Size(s * 0.34f, s * 0.035f))
        }
        MurfHat.Cap -> {
            drawArc(hatC, 180f, 180f, true, Offset(cx - hr * 1.02f, top - s * 0.05f), Size(hr * 2.04f, s * 0.3f))
            drawRoundRect(hatC, Offset(cx, top + s * 0.07f), Size(s * 0.3f, s * 0.05f), CornerRadius(s * 0.025f))
            drawCircle(spec.color.tint(0.5f), s * 0.025f, Offset(cx, top - s * 0.04f))
        }
        MurfHat.Wizard -> {
            val p = Path().apply {
                moveTo(cx - s * 0.24f, top + s * 0.08f); lineTo(cx + s * 0.05f, top - s * 0.3f); lineTo(cx + s * 0.24f, top + s * 0.08f); close()
            }
            drawPath(p, hatC)
            drawOval(hatC, Offset(cx - s * 0.28f, top + s * 0.04f), Size(s * 0.56f, s * 0.08f))
            drawCircle(Color(0xFFFDE68A), s * 0.03f, Offset(cx + s * 0.0f, top - s * 0.08f))
        }
        MurfHat.Beret -> {
            drawOval(hatC, Offset(cx - s * 0.26f, top - s * 0.06f), Size(s * 0.5f, s * 0.16f))
            drawRoundRect(hatC.shade(0.85f), Offset(cx - s * 0.02f, top - s * 0.11f), Size(s * 0.04f, s * 0.06f), CornerRadius(s * 0.02f))
        }
        MurfHat.Flower -> {
            drawArc(hatC, 180f, 180f, true, Offset(cx - hr * 1.05f, top - s * 0.06f), Size(hr * 2.1f, s * 0.26f))
            drawOval(hatC, Offset(cx - s * 0.28f, top + s * 0.04f), Size(s * 0.56f, s * 0.07f))
            val fc = Offset(cx + s * 0.13f, top - s * 0.01f)
            repeat(5) { i ->
                val a = Math.toRadians(i * 72.0)
                drawCircle(Color.White, s * 0.032f, Offset(fc.x + (s * 0.04f * kotlin.math.cos(a)).toFloat(), fc.y + (s * 0.04f * kotlin.math.sin(a)).toFloat()))
            }
            drawCircle(Color(0xFFFACC15), s * 0.028f, fc)
        }
        MurfHat.Helmet -> {
            drawArc(hatC, 180f, 180f, true, Offset(cx - hr * 1.12f, top - s * 0.07f), Size(hr * 2.24f, s * 0.34f))
            drawRoundRect(hatC.shade(0.8f), Offset(cx - hr * 1.2f, top + s * 0.08f), Size(hr * 2.4f, s * 0.04f), CornerRadius(s * 0.02f))
            drawRoundRect(spec.color.tint(0.6f), Offset(cx - s * 0.02f, top - s * 0.06f), Size(s * 0.04f, s * 0.13f), CornerRadius(s * 0.02f))
        }
        MurfHat.TopHat -> {
            drawRoundRect(hatC, Offset(cx - s * 0.26f, top + s * 0.03f), Size(s * 0.52f, s * 0.06f), CornerRadius(s * 0.03f))
            drawRect(hatC, Offset(cx - s * 0.15f, top - s * 0.24f), Size(s * 0.3f, s * 0.28f))
            drawRect(spec.color.tint(0.4f), Offset(cx - s * 0.15f, top - s * 0.02f), Size(s * 0.3f, s * 0.04f))
        }
        MurfHat.Beanie -> {
            drawArc(hatC, 180f, 180f, true, Offset(cx - hr * 1.05f, top - s * 0.08f), Size(hr * 2.1f, s * 0.32f))
            drawRoundRect(spec.color.tint(0.45f), Offset(cx - hr * 1.08f, top + s * 0.04f), Size(hr * 2.16f, s * 0.06f), CornerRadius(s * 0.03f))
            drawCircle(Color.White, s * 0.05f, Offset(cx, top - s * 0.1f))
        }
        MurfHat.Chef -> {
            drawRect(Color.White, Offset(cx - s * 0.15f, top - s * 0.06f), Size(s * 0.3f, s * 0.14f))
            listOf(-0.11f, 0f, 0.11f).forEach { d -> drawCircle(Color.White, s * 0.09f, Offset(cx + d * s, top - s * 0.1f)) }
            drawRect(hatC, Offset(cx - s * 0.15f, top + s * 0.04f), Size(s * 0.3f, s * 0.035f))
        }
        MurfHat.Boater -> {
            val straw = Color(0xFFE9C46A)
            drawOval(straw, Offset(cx - s * 0.32f, top + s * 0.03f), Size(s * 0.64f, s * 0.08f))
            drawRect(straw, Offset(cx - s * 0.17f, top - s * 0.07f), Size(s * 0.34f, s * 0.13f))
            drawRect(hatC, Offset(cx - s * 0.17f, top - s * 0.01f), Size(s * 0.34f, s * 0.04f))
        }
    }
}

/**
 * Значок бота: скруглённый квадрат цвета бота с человечком, значок бэкенда справа снизу (~1/3),
 * статус снаружи: крутящееся кольцо (работает), жёлтая пульсирующая точка (ждёт подтверждения),
 * синяя (непрочитанное), красная (ошибка / нужен вход). Анимации выключаются вместе с остальными.
 */
@Composable
fun MurfBotAvatar(
    spec: MurfAvatarSpec,
    backend: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    status: MurfBotStatus = MurfBotStatus.Idle,
    showBadge: Boolean = true,
    description: String? = null,
) {
    val reduce = rememberReduceMotion()
    val animate = !reduce && (status == MurfBotStatus.Working || status == MurfBotStatus.AwaitingConfirm)
    val water by UiPrefs.waterMascots.collectAsState()
    var spin = 0f; var bob = 0f; var blink = 0f; var pulse = 1f
    if (animate) {
        val t = rememberInfiniteTransition(label = "murf-avatar")
        val sp by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
        val bb by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bob")
        val bl by t.animateFloat(0f, 0f, infiniteRepeatable(keyframes { durationMillis = 3200; 0f at 2900; 1f at 3020; 0f at 3200 }), label = "blink")
        val pl by t.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
        spin = sp; bob = bb; blink = bl; pulse = pl
    }
    val ring = murfBackendColor(backend)
    val pad = size * 0.09f
    Box(modifier.size(size + pad * 2).semantics { description?.let { contentDescription = it } }) {
        if (status == MurfBotStatus.Working) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.minDimension * 0.05f
                rotate(spin) {
                    drawArc(ring.copy(alpha = 0.25f), 0f, 360f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
                    drawArc(ring, 0f, 110f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
        if (water) {
            val shape = RoundedCornerShape(size * 0.3f)
            MurfWaterMascot(
                spec.hat, play = animate,
                modifier = Modifier.padding(pad).size(size).clip(shape).background(spec.color)
                    .border((size * 0.05f).coerceAtLeast(1.5.dp), spec.color, shape),
            )
        } else Box(
            Modifier.padding(pad).size(size).clip(RoundedCornerShape(size * 0.3f)).background(spec.color),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                translate(top = bob * this.size.height * 0.02f) { drawMurfCharacter(spec, if (status == MurfBotStatus.Working) blink else 0f) }
            }
        }
        if (showBadge && size >= 28.dp) {
            backendBadge[backend]?.let { (letter, c) ->
                val bs = (size / 3).coerceAtLeast(14.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(bs).clip(CircleShape).background(c).border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(letter, color = Color.White, fontSize = (bs.value * 0.55f).sp, fontWeight = FontWeight.Bold) }
            }
        }
        val dot = when (status) {
            MurfBotStatus.AwaitingConfirm -> Color(0xFFFACC15)
            MurfBotStatus.Unread -> Color(0xFF3B82F6)
            MurfBotStatus.Error -> Color(0xFFEF4444)
            else -> null
        }
        dot?.let {
            val ds = (size * 0.24f).coerceAtLeast(8.dp)
            val k = if (status == MurfBotStatus.AwaitingConfirm) pulse else 1f
            Canvas(Modifier.align(Alignment.TopEnd).size(ds)) {
                drawCircle(Color.White, this.size.minDimension / 2 * k)
                drawCircle(it, this.size.minDimension / 2 * k - 1.5.dp.toPx())
            }
        }
    }
}

@Composable
fun MurfBotAvatar(bot: BotDto, modifier: Modifier = Modifier, size: Dp = 48.dp, status: MurfBotStatus? = null) =
    MurfBotAvatar(
        MurfAvatarSpec.resolve(bot.emoji, bot.backend, bot.name), bot.backend, modifier, size,
        status ?: if (bot.running > 0) MurfBotStatus.Working else MurfBotStatus.Idle,
        description = bot.name,
    )

/** Выбор персонажа и цвета в настройках бота. value = null — по умолчанию. */
@Composable
fun MurfAvatarPicker(current: MurfAvatarSpec, isDefault: Boolean, backend: String, onPick: (MurfAvatarSpec?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MurfBotAvatar(current, backend, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(tr(current.hat.ru, current.hat.en), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isDefault) tr("по умолчанию", "default") else tr("выбрано", "custom"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isDefault) Text(tr("Сбросить", "Reset"), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onPick(null) }.padding(6.dp))
        }
        MurfHat.entries.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { h ->
                    val sel = h == current.hat
                    Box(Modifier.clip(RoundedCornerShape(12.dp))
                        .border(if (sel) 2.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onPick(current.copy(hat = h)) }.padding(2.dp)) {
                        MurfBotAvatar(current.copy(hat = h), backend, size = 36.dp, showBadge = false, description = tr(h.ru, h.en))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MurfAvatarPalette.colors.forEachIndexed { i, c ->
                val sel = i == current.colorIndex
                Box(Modifier.size(22.dp).clip(CircleShape).background(c)
                    .border(if (sel) 3.dp else 0.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                    .clickable { onPick(current.copy(colorIndex = i)) }
                    .semantics { contentDescription = MurfAvatarPalette.namesRu[i] })
            }
        }
    }
}

/** Все персонажи (для проверки и превью). */
@Composable
fun MurfAvatarGallery(modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MurfHat.entries.chunked(5).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, h ->
                    val be = MurfAvatarSpec.backendDefaults.entries.firstOrNull { it.value.hat == h }
                    MurfBotAvatar(be?.value ?: MurfAvatarSpec(h, h.ordinal), be?.key, size = 56.dp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MurfBotStatus.entries.forEach { st -> MurfBotAvatar(MurfAvatarSpec(MurfHat.WideBrim, 0), "hermes", size = 56.dp, status = st) }
        }
    }
}
