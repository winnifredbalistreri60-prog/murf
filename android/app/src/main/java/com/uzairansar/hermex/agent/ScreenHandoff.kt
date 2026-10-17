package com.uzairansar.hermex.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject
import java.util.UUID
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MURF «Взять экран»: человек забирает живой экран агента, агент ждёт; «Я закончила» — управление
 * возвращается агенту. На сервере это родная аренда Hermes Bot Desktop (lease.json): пока она у человека,
 * computer_use и браузер агента отказываются действовать (human_has_control).
 * Если на сервере нет Hermes Bot Desktop, работает только переключение «Смотреть/Управлять» (supported=false).
 */
object ScreenHandoff {
    data class State(
        /** Экран у этого телефона (режим «Управлять» включён через «Взять экран»). */
        val holding: Boolean = false,
        /** Сервер подтвердил аренду: агент действительно не трогает экран. */
        val agentPaused: Boolean = false,
        /** Аренда Hermes поддерживается сервером (null — ещё не знаем). */
        val supported: Boolean? = null,
        /** Экран держит другой человек (Hermes Desktop или другой телефон). */
        val heldByOther: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val sinceMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: HttpUrl? = null
    @Volatile private var jar: CookieJar? = null
    private var viewer: String = "murf-" + UUID.randomUUID().toString().replace("-", "").take(16)

    /** Сообщение агенту после «Я закончила» (чат подхватывает и отправляет/ставит в очередь). */
    private val _handBackMessages = MutableStateFlow(0L)
    val handBackEvents: StateFlow<Long> = _handBackMessages
    val handBackText: String get() = tr(
        "Я закончила с экраном, управление снова у тебя. Продолжай.",
        "I'm done with the screen, you have control again. Please continue.",
    )

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences("murf_ui", Context.MODE_PRIVATE)
        viewer = sp.getString("screen_viewer_id", null) ?: viewer.also { sp.edit().putString("screen_viewer_id", it).apply() }
    }

    fun bind(server: HttpUrl, jar: CookieJar) {
        this.server = server
        this.jar = jar
        refresh()
    }

    /** Чистая логика разбора ответа сервера (для тестов). */
    internal fun parse(j: JSONObject, prev: State): State {
        if (!j.optBoolean("supported", true) && j.has("supported")) {
            return prev.copy(supported = false, agentPaused = false, heldByOther = false, busy = false, error = null)
        }
        val human = j.optString("holder") == "human"
        val mine = j.optBoolean("mine", false)
        return prev.copy(
            supported = true,
            holding = if (human && mine) true else if (!human) false else prev.holding && mine,
            agentPaused = human,
            heldByOther = human && !mine,
            busy = false,
            error = if (j.optBoolean("ok", true)) null else j.optString("error").ifBlank { null },
            sinceMs = if (human) (j.optDouble("since", 0.0) * 1000).toLong().takeIf { it > 0 } else null,
        )
    }

    fun refresh() {
        val s = server ?: return
        val c = jar ?: return
        scope.launch {
            runCatching {
                val url = s.newBuilder().encodedPath("/phone/api/screen/handoff").addQueryParameter("viewer", viewer).build()
                ServerUpdatesApi.get(url, c)
            }.onSuccess { j ->
                val next = parse(j, _state.value)
                _state.value = next
                // экран у нас на сервере, а приложение было перезапущено — вернуть режим «Управлять»
                if (next.holding && !UiPrefs.screenControl.value) UiPrefs.setScreenControlRaw(true)
            }
        }
    }

    /** «Взять экран»: включить управление и попросить агента подождать. */
    fun take() {
        UiPrefs.setScreenControlRaw(true)
        _state.value = _state.value.copy(holding = true, busy = true, error = null, sinceMs = System.currentTimeMillis())
        val s = server; val c = jar
        if (s == null || c == null) { _state.value = _state.value.copy(busy = false); return }
        scope.launch {
            runCatching {
                ServerUpdatesApi.post(s, c, "/phone/api/screen/handoff", JSONObject().put("action", "take").put("viewer", viewer).put("reason", tr("MURF: управление с телефона", "MURF: phone has control")))
            }.onSuccess { _state.value = parse(it, _state.value).copy(holding = true) }
                .onFailure { _state.value = _state.value.copy(busy = false, error = tr("Не удалось сообщить серверу: агент может продолжать работать на экране.", "Could not notify the server: the agent may keep acting on the screen.")) }
        }
    }

    /** «Я закончила»: вернуть экран агенту. [notifyAgent] — отправить агенту сообщение «продолжай». */
    fun release(notifyAgent: Boolean, force: Boolean = false) {
        UiPrefs.setScreenControlRaw(false)
        val wasPaused = _state.value.agentPaused || _state.value.holding
        _state.value = _state.value.copy(holding = false, busy = true, error = null)
        val s = server; val c = jar
        if (notifyAgent && wasPaused) {
            val now = System.currentTimeMillis()
            pendingHandBackAt.set(now)
            _handBackMessages.value = now
        }
        if (s == null || c == null) { _state.value = _state.value.copy(busy = false); return }
        scope.launch {
            runCatching {
                ServerUpdatesApi.post(s, c, "/phone/api/screen/handoff", JSONObject().put("action", "release").put("viewer", viewer).put("force", force))
            }.onSuccess { _state.value = parse(it, _state.value).copy(holding = false) }
                .onFailure { _state.value = _state.value.copy(busy = false, error = tr("Сервер не ответил. Экран может остаться за вами — нажмите «Я закончила» ещё раз.", "The server did not respond. Tap “I'm done” again.")) }
        }
    }

    private val pendingHandBackAt = java.util.concurrent.atomic.AtomicLong(0)

    /** Забрать событие «Я закончила» (один раз, не старше 10 минут) — чтобы сообщение агенту ушло из открытого чата. */
    fun consumeHandBack(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = pendingHandBackAt.getAndSet(0)
        return at > 0 && nowMs - at < 10 * 60_000L
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

/** Плашка «Экран у вас — агент ждёт» с кнопкой «Я закончила». */
@androidx.compose.runtime.Composable
fun ScreenHandoffBanner(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val st by ScreenHandoff.state.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = st.holding || st.error != null,
        modifier = modifier,
        enter = if (rememberReduceMotion()) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = if (rememberReduceMotion()) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF3A2A12))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .testTag("screen_handoff_banner"),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    if (st.holding) tr("✋ Экран у вас", "✋ You have the screen") else tr("Экран", "Screen"),
                    color = androidx.compose.ui.graphics.Color(0xFFFFD36B), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                val sub = st.error ?: when {
                    st.busy -> tr("Связь с сервером…", "Contacting the server…")
                    st.supported == false -> tr("Сервер не умеет ставить агента на паузу — договоритесь с ним в чате.", "The server can't pause the agent — tell it in chat.")
                    st.agentPaused -> tr("Агент ждёт и не трогает экран", "The agent waits and won't touch the screen")
                    else -> tr("Управление включено", "Control is on")
                }
                androidx.compose.material3.Text(sub, color = androidx.compose.ui.graphics.Color(0xFFE8DCC4), fontSize = 11.sp, maxLines = 2)
            }
            if (st.holding) {
                androidx.compose.material3.Button(
                    onClick = { ScreenHandoff.release(notifyAgent = true) },
                    modifier = androidx.compose.ui.Modifier.testTag("screen_handoff_done"),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF5C400), contentColor = androidx.compose.ui.graphics.Color(0xFF111111)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { androidx.compose.material3.Text(tr("Я закончила", "I'm done"), fontSize = 13.sp) }
            } else {
                androidx.compose.material3.TextButton(onClick = { ScreenHandoff.clearError() }) { androidx.compose.material3.Text("OK") }
            }
        }
    }
}
