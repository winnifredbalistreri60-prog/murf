package com.uzairansar.hermex.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Учёт токенов в MURF — по образцу DeepSeek Harness (MIT, (c) 2026 DeepSeek; TurnUsagePanel / StatsPills / token-meter):
 * только то, что сообщил провайдер; корзина (кэш, рассуждения) показывается, только если её сообщили все попытки,
 * иначе «н/д». Общая модель — её заполняют DeepSeek Harness, позже Hermes и CLI-боты.
 */
data class TokenUsage(
    val input: Long,            // некэшированный ввод
    val output: Long,
    val total: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val reasoning: Long? = null,
) {
    val prompt: Long get() = total?.let { it - output } ?: (input + (cacheRead ?: 0) + (cacheWrite ?: 0))
    val sum: Long get() = total ?: (prompt + output)
    /** Доля попаданий в кэш, % от всего ввода; null, если провайдер не сообщил кэш. */
    val cacheHitPct: Double? get() = cacheRead?.let { if (prompt > 0) it * 100.0 / prompt else null }

    operator fun plus(o: TokenUsage) = TokenUsage(
        input + o.input, output + o.output,
        if (total != null && o.total != null) total + o.total else null,
        both(cacheRead, o.cacheRead), both(cacheWrite, o.cacheWrite), both(reasoning, o.reasoning),
    )

    companion object {
        private fun both(a: Long?, b: Long?) = if (a != null && b != null) a + b else null
        /** Формат DSH: {inputTokens, outputTokens, totalTokens, cacheReadTokens?, cacheWriteTokens?, reasoningTokens?}. */
        fun fromDsh(o: org.json.JSONObject?): TokenUsage? {
            if (o == null || !o.has("inputTokens") || !o.has("outputTokens")) return null
            fun opt(k: String) = if (o.has(k)) o.optLong(k) else null
            return TokenUsage(o.optLong("inputTokens"), o.optLong("outputTokens"), opt("totalTokens"),
                opt("cacheReadTokens"), opt("cacheWriteTokens"), opt("reasoningTokens"))
        }
    }
}

/** Использование одного хода (turn): маршрут, токены, время. */
data class TurnStats(
    val provider: String,
    val model: String,
    val usage: TokenUsage?,          // null — хотя бы одна попытка не сообщила usage
    val steps: Int,
    val modelMs: Long,
    val toolMs: Long,
    val ttftMs: Long?,
    val outputMs: Long,
)

data class SessionStats(val turns: List<TurnStats>) {
    val steps get() = turns.sumOf { it.steps }
    val usage: TokenUsage? get() = turns.mapNotNull { it.usage }.takeIf { it.size == turns.size && it.isNotEmpty() }?.reduce { a, b -> a + b }
    val modelMs get() = turns.sumOf { it.modelMs }
    val toolMs get() = turns.sumOf { it.toolMs }
    val avgTtftMs: Long? get() = turns.mapNotNull { it.ttftMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val tokensPerSec: Double? get() {
        val out = turns.mapNotNull { it.usage?.output }.sum(); val ms = turns.sumOf { it.outputMs }
        return if (out > 0 && ms > 0) out * 1000.0 / ms else null
    }
}

/** Цены, $ за 1 млн токенов (ввод, кэш-чтение, вывод). Ориентировочно, на 10.2026; провайдеры меняют цены. */
object MurfPrices {
    data class Price(val input: Double, val cacheRead: Double, val output: Double, val cacheWrite: Double? = null)
    private val table: List<Pair<Regex, Price>> = listOf(
        Regex("deepseek.*(reason|r1)") to Price(0.55, 0.14, 2.19),
        Regex("deepseek") to Price(0.27, 0.07, 1.10),
        Regex("gpt-5.*mini") to Price(0.25, 0.025, 2.0),
        Regex("gpt-5.*nano") to Price(0.05, 0.005, 0.40),
        Regex("gpt-5") to Price(1.25, 0.125, 10.0),
        Regex("gpt-4\\.1-mini") to Price(0.40, 0.10, 1.60),
        Regex("gpt-4o-mini") to Price(0.15, 0.075, 0.60),
        Regex("gpt-4o|gpt-4\\.1") to Price(2.50, 1.25, 10.0),
        Regex("claude.*opus") to Price(15.0, 1.50, 75.0, 18.75),
        Regex("claude.*haiku") to Price(1.0, 0.10, 5.0, 1.25),
        Regex("claude") to Price(3.0, 0.30, 15.0, 3.75),
        Regex("gemini.*flash-lite") to Price(0.10, 0.025, 0.40),
        Regex("gemini.*flash") to Price(0.30, 0.075, 2.50),
        Regex("gemini.*pro") to Price(1.25, 0.31, 10.0),
        Regex("grok.*(mini|fast)") to Price(0.20, 0.05, 0.50),
        Regex("grok") to Price(3.0, 0.75, 15.0),
    )
    fun find(model: String): Price? {
        val m = model.lowercase(Locale.ROOT).substringAfterLast('/')
        if (m.endsWith(":free")) return Price(0.0, 0.0, 0.0)
        return table.firstOrNull { it.first.containsMatchIn(m) }?.second
    }
    /** Примерная стоимость в $; null, если цены модели нет в таблице. */
    fun cost(model: String, u: TokenUsage): Double? {
        val p = find(model) ?: return null
        return (u.input * p.input + (u.cacheRead ?: 0) * p.cacheRead + (u.cacheWrite ?: 0) * (p.cacheWrite ?: p.input) +
            u.output * p.output) / 1_000_000.0
    }
}

/** Свёртка событий `dsh --profile headless --json` (+ поле _t от murf_api) в статистику ходов. */
class DshUsageAccumulator {
    val turns = mutableListOf<TurnStats>()
    private var provider = ""; private var model = ""
    private var stepStart = 0L; private var firstText: Long? = null; private var steps = 0
    private var usage: TokenUsage? = null; private var usageComplete = true
    private var modelMs = 0L; private var toolMs = 0L; private var outMs = 0L; private var ttft: Long? = null
    private val toolStart = HashMap<String, Long>()

    fun route(provider: String, model: String) { this.provider = provider; this.model = model }

    fun add(e: org.json.JSONObject) {
        val t = e.optLong("_t")
        when (e.optString("type")) {
            "murf_run" -> route(e.optString("provider"), e.optString("model"))
            "status" -> when (e.optString("phase")) {
                "turn_start" -> { steps = 0; usage = null; usageComplete = true; modelMs = 0; toolMs = 0; outMs = 0; ttft = null }
                "step_start" -> { stepStart = t; firstText = null; steps++ }
                "step_end" -> {
                    val d = (t - stepStart).coerceAtLeast(0); modelMs += d
                    firstText?.let { ft -> if (ttft == null) ttft = ft - stepStart; outMs += (t - ft).coerceAtLeast(1) }
                    val u = TokenUsage.fromDsh(e.optJSONObject("usage"))
                    if (u == null) usageComplete = false else usage = usage?.plus(u) ?: u
                }
                "turn_end" -> turns += TurnStats(provider, model, usage.takeIf { usageComplete }, steps, modelMs, toolMs, ttft, outMs)
            }
            "text", "thinking" -> if (firstText == null) firstText = t
            "tool_call" -> toolStart[e.optString("callId")] = t
            "tool_result" -> toolStart.remove(e.optString("callId"))?.let { toolMs += (t - it).coerceAtLeast(0) }
        }
    }
    fun session() = SessionStats(turns.toList())
}

fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", n / 1e6)
    n >= 10_000 -> String.format(Locale.ROOT, "%.1fk", n / 1e3)
    else -> n.toString()
}
private fun fmtExact(n: Long?) = n?.let { String.format(Locale("ru"), "%,d", it) + " tok" } ?: tr("н/д", "n/a")
private fun fmtMs(ms: Long?) = ms?.let { String.format(Locale.ROOT, if (it >= 10_000) "%.0f " else "%.1f ", it / 1000.0) + tr("с", "s") } ?: tr("н/д", "n/a")
fun fmtCost(c: Double?) = c?.let { "≈ $" + if (it < 0.01) String.format(Locale.ROOT, "%.4f", it) else String.format(Locale.ROOT, "%.2f", it) } ?: tr("цена неизвестна", "price unknown")

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** Кнопка «Использовано N» под ответом → окно с подробностями хода (как TurnUsagePanel в DSH). */
@Composable
fun TurnUsageChip(turn: TurnStats) {
    var open by remember { mutableStateOf(false) }
    val u = turn.usage
    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, modifier = Modifier.clickable { open = true }) {
        Text(
            tr("Использовано ", "Usage ") + (u?.let { fmtTokens(it.sum) } ?: tr("н/д", "n/a")) +
                (u?.cacheHitPct?.let { String.format(Locale.ROOT, " · " + tr("кэш", "cache") + " %.0f%%", it) } ?: ""),
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
        )
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, confirmButton = { TextButton({ open = false }) { Text("OK") } },
        title = { Text(tr("Использование за ход", "Turn usage")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kv(tr("Провайдер / модель", "Provider / model"), "${turn.provider} / ${turn.model}")
                if (u == null) Text(tr("Провайдер не сообщил расход хотя бы для одной попытки — точных цифр нет.", "The provider did not report usage for at least one attempt."), style = MaterialTheme.typography.bodySmall)
                else {
                    Kv(tr("Попадание в кэш", "Cache hit"), u.cacheHitPct?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: tr("н/д", "n/a"))
                    Kv(tr("Некэшированный ввод", "Uncached input"), fmtExact(u.input))
                    Kv(tr("Чтение кэша", "Cache read"), fmtExact(u.cacheRead))
                    Kv(tr("Запись в кэш", "Cache write"), fmtExact(u.cacheWrite))
                    Kv(tr("Вывод", "Output"), fmtExact(u.output))
                    Kv(tr("  из них рассуждения", "  of which reasoning"), fmtExact(u.reasoning))
                    Kv(tr("Всего", "Total"), fmtExact(u.sum))
                    Kv(tr("Стоимость (примерно)", "Cost (approx.)"), fmtCost(MurfPrices.cost(turn.model, u)))
                }
                Kv(tr("Шагов", "Steps"), turn.steps.toString())
                Kv(tr("Время модели / инструментов", "Model / tool time"), fmtMs(turn.modelMs) + " / " + fmtMs(turn.toolMs))
                Kv(tr("Первый токен (TTFT)", "Time to first token"), fmtMs(turn.ttftMs))
                Text(tr("Время меряет MURF по потоку событий — приблизительно. Цена — по таблице MURF, не счёт провайдера.",
                    "Timing is measured by MURF from the event stream (approximate). Cost uses MURF's price table, not the provider's bill."),
                    style = MaterialTheme.typography.labelSmall)
            }
        })
}

/** Плашка статистики сессии (как StatsPills + «Статистика сессии» в DSH). */
@Composable
fun SessionStatsBar(stats: SessionStats, model: String) {
    var open by remember { mutableStateOf(false) }
    val u = stats.usage
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Text(
            tr("${stats.turns.size} ход. · ${stats.steps} шаг.", "${stats.turns.size} turns · ${stats.steps} steps") +
                (u?.let { " · " + fmtTokens(it.sum) + " tok" } ?: "") +
                (u?.cacheHitPct?.let { String.format(Locale.ROOT, " · " + tr("кэш", "cache") + " %.0f%%", it) } ?: "") +
                (u?.let { " · " + fmtCost(MurfPrices.cost(model, it)) } ?: ""),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
        )
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, confirmButton = { TextButton({ open = false }) { Text("OK") } },
        title = { Text(tr("Статистика сессии", "Session statistics")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kv(tr("Ходов / шагов", "Turns / steps"), "${stats.turns.size} / ${stats.steps}")
                Kv(tr("Попадание в кэш", "Cache hit"), u?.cacheHitPct?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: tr("н/д", "n/a"))
                Kv(tr("Некэшированный ввод", "Uncached input"), fmtExact(u?.input))
                Kv(tr("Чтение кэша", "Cache read"), fmtExact(u?.cacheRead))
                Kv(tr("Запись в кэш", "Cache write"), fmtExact(u?.cacheWrite))
                Kv(tr("Вывод", "Output"), fmtExact(u?.output))
                Kv(tr("Время модели", "LLM time"), fmtMs(stats.modelMs))
                Kv(tr("Время инструментов", "Tool time"), fmtMs(stats.toolMs))
                Kv(tr("Первый токен, в среднем", "Avg TTFT"), fmtMs(stats.avgTtftMs))
                Kv(tr("Скорость вывода", "Output speed"), stats.tokensPerSec?.let { String.format(Locale.ROOT, "%.1f tok/s", it) } ?: tr("н/д", "n/a"))
                Kv(tr("Стоимость (примерно)", "Cost (approx.)"), u?.let { fmtCost(MurfPrices.cost(model, it)) } ?: tr("н/д", "n/a"))
            }
        })
}
