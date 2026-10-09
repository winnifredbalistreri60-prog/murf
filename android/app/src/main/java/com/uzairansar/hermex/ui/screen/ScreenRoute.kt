package com.uzairansar.hermex.ui.screen

import com.uzairansar.hermex.agent.tr

import com.uzairansar.hermex.agent.horizontalSwipeNav
import com.uzairansar.hermex.agent.swipeTransparentInterop
import com.uzairansar.hermex.agent.swipeBlockingInterop
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import com.uzairansar.hermex.R
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * URL страницы живого экрана (noVNC) за cookie WebUI.
 * mode=view — только просмотр (сервер отбрасывает ввод), mode=control — управление (отдельный websockify).
 */
fun screenUrl(server: HttpUrl, mini: Boolean = false, control: Boolean = false): String =
    server.newBuilder().encodedPath("/screen/").apply {
        if (mini) addQueryParameter("mini", "1")
        else {
            addQueryParameter("app", "1")
            addQueryParameter("mode", if (control) "control" else "view")
        }
    }.build().toString()

/** Copies the WebUI session cookies from the app's cookie jar into the WebView cookie store. */
fun syncScreenCookies(server: HttpUrl, cookies: List<Cookie>) {
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    val origin = "${server.scheme}://${server.host}"
    cookies.forEach { cookie ->
        val attrs = buildString {
            append("${cookie.name}=${cookie.value}; Path=${cookie.path}")
            if (cookie.secure) append("; Secure")
            if (cookie.httpOnly) append("; HttpOnly")
        }
        manager.setCookie(origin, attrs)
    }
    manager.flush()
}

@SuppressLint("SetJavaScriptEnabled")
fun createScreenWebView(context: android.content.Context): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Статика noVNC кэшируется (сервер отдаёт её с max-age, а саму страницу — no-store), иначе каждое открытие экрана — ~40 загрузок
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Щипок для увеличения экрана агента; кнопки зума не показываем.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        setBackgroundColor(android.graphics.Color.BLACK)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                request.url.path?.startsWith("/screen/") != true
        }
        webChromeClient = WebChromeClient()
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    }

@Composable
fun LiveScreenView(
    url: String,
    modifier: Modifier = Modifier,
    reloadKey: Int = 0,
    onWebView: (WebView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            createScreenWebView(context).apply {
                loadUrl(url)
                tag = reloadKey to url
            }.also(onWebView)
        },
        update = { view ->
            if (view.tag != (reloadKey to url)) {
                view.tag = reloadKey to url
                view.loadUrl(url)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenRoute(
    server: HttpUrl,
    cookies: List<Cookie>,
    onBack: () -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var snapMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    remember(server, cookies) { syncScreenCookies(server, cookies); true }
    fun snapshot(kind: String) {
        val wv = webView ?: return
        wv.evaluateJavascript("(function(){var c=document.querySelector('canvas');return c&&c.width?c.toDataURL('image/png'):''})()") { raw ->
            val data = raw?.trim('"').orEmpty()
            val b64 = data.substringAfter("base64,", "")
            if (b64.isBlank()) {
                com.uzairansar.hermex.agent.AgentShare.toast(context, tr("Экран ещё не показан — нечего сохранять", "The screen is not shown yet — nothing to save"))
                return@evaluateJavascript
            }
            val share = com.uzairansar.hermex.agent.AgentShare
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val name = "agent-screen-${share.stamp()}.png"
                when (kind) {
                    "share" -> share.shareFile(context, share.writeExport(context, name, bytes), "image/png", tr("Снимок экрана агента", "Agent screenshot"))
                    "drive" -> share.saveFileToDrive(context, share.writeExport(context, name, bytes), "image/png")
                    else -> share.toast(context, tr("Сохранено: ", "Saved: ") + share.saveToDownloads(context, name, "image/png", bytes))
                }
            }.onFailure { share.toast(context, tr("Ошибка: ${it.message}", "Error: ${it.message}")) }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Экран агента", "Agent screen")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), contentDescription = tr("Назад", "Back")) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { snapMenu = true }) { Icon(painterResource(R.drawable.ic_hermex_external_link), contentDescription = tr("Снимок экрана", "Screenshot")) }
                        androidx.compose.material3.DropdownMenu(expanded = snapMenu, onDismissRequest = { snapMenu = false }) {
                            listOf("share" to tr("Поделиться снимком", "Share screenshot"), "downloads" to tr("Снимок в Загрузки", "Screenshot to Downloads"), "drive" to tr("Снимок в Google Диск", "Screenshot to Google Drive")).forEach { (k, label) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { snapMenu = false; snapshot(k) })
                            }
                        }
                    }
                    IconButton(onClick = { reloadKey++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), contentDescription = tr("Обновить", "Update")) }
                },
            )
        },
    ) { padding ->
        val control by com.uzairansar.hermex.agent.UiPrefs.screenControl.collectAsState()
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.uzairansar.hermex.agent.ScreenModeToggle(control, com.uzairansar.hermex.agent.UiPrefs::setScreenControl)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (control) tr("Тап — клик, удержание — правый клик, 2 пальца — прокрутка", "Tap — click, hold — right click, 2 fingers — scroll") else tr("Только просмотр", "View only"),
                    color = Color(0xFFB4B4BA),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // «Смотреть»: noVNC только показывает картинку — свайпы разделов работают и поверх него.
                // «Управлять»: касания уходят на экран агента, свайпы приложения над экраном отключены.
                LiveScreenView(
                    url = screenUrl(server, control = control),
                    modifier = Modifier.fillMaxSize().then(if (control) Modifier.swipeBlockingInterop() else Modifier.swipeTransparentInterop()),
                    reloadKey = reloadKey,
                    onWebView = { webView = it },
                )
                // локальный профиль: через 15 с без соединения — причина и «Перезапустить экран» / «Показать журнал»
                LocalScreenGuard(server, { webView }, { reloadKey++ }, Modifier.align(Alignment.BottomCenter), key = reloadKey to control)
            }
        }
    }
}

/** Small live preview over the chat while the agent works (opt-in): tap opens the full screen, drag to move, × hides it. */
@Composable
fun MiniScreenPreview(
    server: HttpUrl,
    cookies: List<Cookie>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    width: Dp = 168.dp,
    height: Dp = 105.dp,
) {
    remember(server, cookies) { syncScreenCookies(server, cookies); true }
    var dx by rememberSaveable { mutableFloatStateOf(0f) }
    var dy by rememberSaveable { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .size(width, height)
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); dx += drag.x; dy += drag.y } }
            .testTag("chat_mini_preview"),
    ) {
        LiveScreenView(url = screenUrl(server, mini = true), modifier = Modifier.fillMaxSize())
        // Transparent layer: WebView must not eat the tap.
        Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
        Text(
            tr("Экран", "Screen"),
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (onDismiss != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(26.dp).clip(CircleShape).background(Color(0xB3000000))
                    .clickable(onClick = onDismiss).semantics { contentDescription = tr("Скрыть превью", "Hide preview") }
                    .testTag("chat_mini_preview_close"),
                contentAlignment = Alignment.Center,
            ) { Text("×", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Один WebView экрана на весь чат: при повторном открытии оверлея noVNC не переподключается.
 * После долгого простоя в закрытом виде соединение «усыпляется» (about:blank), при открытии — снова грузится.
 */
class ScreenWebViewHolder {
    var webView: WebView? = null
        private set
    private var loadedUrl: String? = null

    fun obtain(context: android.content.Context, url: String): WebView {
        val wv = webView ?: createScreenWebView(context).also { webView = it }
        (wv.parent as? ViewGroup)?.removeView(wv)
        if (loadedUrl != url) {
            wv.loadUrl(url)
            loadedUrl = url
        }
        return wv
    }

    /** Переключает страницу (например, Смотреть ↔ Управлять), не отсоединяя WebView. */
    fun load(url: String) {
        val wv = webView ?: return
        if (loadedUrl != url) {
            wv.loadUrl(url)
            loadedUrl = url
        }
    }

    fun reload() {
        val url = loadedUrl ?: return
        webView?.loadUrl(url)
    }

    fun sleep() {
        val wv = webView ?: return
        if (wv.parent != null) return
        wv.loadUrl("about:blank")
        loadedUrl = null
    }

    fun destroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        loadedUrl = null
    }
}

@Composable
fun rememberScreenWebViewHolder(): ScreenWebViewHolder {
    val holder = remember { ScreenWebViewHolder() }
    DisposableEffect(holder) { onDispose { holder.destroy() } }
    return holder
}

private val LiveGreen = Color(0xFF34C759)

/** Зелёная пульсирующая точка «агент работает». */
@Composable
fun LiveDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    if (!active) {
        Box(modifier.size(size).clip(CircleShape).background(Color(0xFF8A8A8E)))
        return
    }
    val transition = rememberInfiniteTransition(label = "live-dot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "live-pulse",
    )
    Box(modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val scale = 1f + pulse * 1.3f
                    scaleX = scale
                    scaleY = scale
                    alpha = (1f - pulse) * 0.6f
                }
                .clip(CircleShape)
                .background(LiveGreen),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(LiveGreen)
                .border(1.5.dp, Color(0xFF101012), CircleShape),
        )
    }
}

@Composable
private fun OverlayRoundButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF26262B))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Живой экран агента поверх чата (как в Grok): не уходит из сессии, чат и стрим продолжают работать под ним.
 * Компактный режим оставляет снизу поле ввода — можно писать агенту, глядя на экран; кнопка «развернуть» — на весь экран.
 * Закрытие: крестик, жест «назад» или свайп вниз за шапку/ручку.
 */
@Composable
fun AgentScreenOverlay(
    server: HttpUrl,
    cookies: List<Cookie>,
    holder: ScreenWebViewHolder,
    isLive: Boolean,
    compactBottomInset: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    remember(server, cookies) { syncScreenCookies(server, cookies); true }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val closeThresholdPx = with(density) { 120.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dragState = rememberDraggableState { delta ->
        scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
    }
    fun Modifier.dragToClose() = draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity ->
            if (offsetY.value > closeThresholdPx || velocity > 2500f) {
                onClose()
            } else {
                offsetY.animateTo(0f)
            }
        },
    )
    val shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    val swipesOn by com.uzairansar.hermex.agent.UiPrefs.swipesEnabled.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (expanded) Modifier else Modifier.imePadding().navigationBarsPadding().padding(bottom = compactBottomInset))
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Color(0xFF0E0E11))
            .horizontalSwipeNav(enabled = swipesOn, onSwipeLeft = null, onSwipeRight = onClose)
            .testTag("chat_screen_overlay"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .dragToClose()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayRoundButton(R.drawable.ic_hermex_xmark, tr("Закрыть экран", "Close screen"), onClose, Modifier.testTag("chat_screen_close"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Экран агента", "Agent screen"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(isLive, size = 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isLive) tr("Агент работает — в эфире", "Agent is working — live") else tr("Ожидание задачи", "Waiting for a task"),
                        color = Color(0xFFB4B4BA),
                        fontSize = 12.sp,
                    )
                }
            }
            OverlayRoundButton(R.drawable.ic_hermex_refresh, tr("Переподключить", "Reconnect"), { holder.reload() })
            Spacer(Modifier.width(8.dp))
            OverlayRoundButton(
                if (expanded) R.drawable.ic_lucide_minimize_2 else R.drawable.ic_lucide_maximize_2,
                if (expanded) tr("Свернуть", "Minimize") else tr("На весь экран", "Full screen"),
                { expanded = !expanded },
                Modifier.testTag("chat_screen_expand"),
            )
        }
        val control by com.uzairansar.hermex.agent.UiPrefs.screenControl.collectAsState()
        var askControl by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.uzairansar.hermex.agent.ScreenModeToggle(control, { want ->
                if (want && isLive) askControl = true else com.uzairansar.hermex.agent.UiPrefs.setScreenControl(want)
            })
            if (control && isLive) {
                Spacer(Modifier.width(8.dp))
                Text(tr("⚠ агент работает", "⚠ agent is working"), color = Color(0xFFFFD36B), fontSize = 11.sp)
            }
        }
        if (askControl) {
            com.uzairansar.hermex.agent.ScreenControlConfirmDialog(
                onConfirm = { askControl = false; com.uzairansar.hermex.agent.UiPrefs.setScreenControl(true) },
                onDismiss = { askControl = false },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            val url = remember(server, control) { screenUrl(server, control = control) }
            AndroidView(
                // В режиме «Управлять» WebView забирает все касания — свайп «вправо = закрыть» над экраном не срабатывает.
                modifier = Modifier.fillMaxSize().then(if (control) Modifier.swipeBlockingInterop() else Modifier.swipeTransparentInterop()),
                factory = { context -> holder.obtain(context, url) },
                update = { holder.load(url) },
            )
            LocalScreenGuard(server, { holder.webView }, { holder.reload() }, Modifier.align(Alignment.BottomCenter), key = url)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .dragToClose()
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(Color(0x66FFFFFF)))
        }
    }
}
