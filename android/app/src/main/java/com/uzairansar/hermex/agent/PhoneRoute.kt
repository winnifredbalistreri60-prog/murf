package com.uzairansar.hermex.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.R
import com.uzairansar.hermex.ui.localization.localizedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun http(jar: CookieJar) = OkHttpClient.Builder().cookieJar(jar)
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

private fun getJson(jar: CookieJar, url: HttpUrl): String =
    http(jar).newCall(Request.Builder().url(url).get().build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string()
    }

private fun postJson(jar: CookieJar, url: HttpUrl, body: String): String =
    http(jar).newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build())
        .execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CodeBox(code: String, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            TextButton(onClick = onCopy) { Text(localizedString("Copy")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneRoute(server: HttpUrl, cookieJar: CookieJar, onBack: () -> Unit) {
    val context = LocalContext.current
    val ui by PhoneBridge.ui.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val termuxInstalled = remember(refresh) { Termux.isInstalled(context) }
    val termuxApi = remember(refresh) { Termux.isInstalled(context, Termux.API_PACKAGE) }
    val termuxPerm = remember(refresh) { Termux.hasPermission(context) }
    var enabled by remember { mutableStateOf(PhoneBridgeSettings.enabled(context)) }
    var auto by remember { mutableStateOf(PhoneBridgeSettings.autoAllowed(context)) }
    var sms by remember { mutableStateOf(PhoneBridgeSettings.smsEnabled(context)) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(localizedString("Phone & Termux")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), localizedString("Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), localizedString("Update")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(localizedString("Phone bridge")) {
                Text(localizedString("When the bridge is on, the agent on the server can ask the phone to do something: a Termux command, a photo, location, file transfer. Each request is confirmed here or in a notification, except those allowed “always”."),
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) tr("Включён", "On") else tr("Выключен", "Off"), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(checked = enabled, onCheckedChange = { v ->
                        enabled = v
                        if (v) {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            PhoneBridge.start(context)
                        } else PhoneBridge.stop(context)
                    })
                }
                Text(
                    when {
                        !enabled -> localizedString("The agent can't see the phone.")
                        ui.connected -> localizedStringFormat("● Connected to %1\$s — the agent sees the phone", server.host)
                        else -> localizedString("○ Connecting… ") + (ui.lastError ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section(localizedString("On-phone agent (DeepSeek Harness)")) {
                val a11y = remember(refresh) { MurfAccessibilityService.enabled(context) }
                val notif = remember(refresh) { MurfNotificationListener.enabled(context) }
                Text(if (ui.localConnected) localizedString("● Bridge connected to the local environment")
                    else localizedString("○ Local environment: bridge not connected"),
                    style = MaterialTheme.typography.bodySmall)
                Text((if (a11y) "✓" else "✗") + localizedString(" Screen control (accessibility): screenshot, screen contents, taps, swipes, typing"))
                Text((if (notif) "✓" else "✗") + localizedString(" Notification access (read for the agent)"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { context.startActivity(MurfAccessibilityService.settingsIntent()) }) { Text(localizedString("Accessibility")) }
                    OutlinedButton(onClick = { context.startActivity(MurfNotificationListener.settingsIntent()) }) { Text(localizedString("Notifications")) }
                }
                Text(localizedString("Taps, swipes and typing in other apps are confirmed every time (no “Always”). Files outside Download/MURF, links, screenshots, clipboard, location and Termux commands need confirmation. SMS is off by default. “Take screen” or “Stop” in chat immediately blocks screen actions."),
                    style = MaterialTheme.typography.labelSmall)
                if (MurfAccessibilityService.userHasControl) TextButton(onClick = { MurfAccessibilityService.userHasControl = false; refresh++ }) {
                    Text(localizedString("Give the screen back to the agent"))
                }
            }

            if (ui.pending.isNotEmpty()) {
                Section(localizedString("Awaiting confirmation")) {
                    ui.pending.forEach { req ->
                        val tool = PhoneTool.of(req.tool)
                        Text("${tool?.title ?: req.tool}", fontWeight = FontWeight.Medium)
                        if (req.summary.isNotBlank()) Text(req.summary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { PhoneBridge.decide(context, req.id, "allow") }) { Text(localizedString("Allow")) }
                            OutlinedButton(onClick = { PhoneBridge.decide(context, req.id, "deny") }) { Text(localizedString("Deny")) }
                            if (tool?.screenControl != true) TextButton(onClick = { PhoneBridge.decide(context, req.id, "always"); auto = PhoneBridgeSettings.autoAllowed(context) + req.tool }) { Text(localizedString("Always")) }
                        }
                    }
                }
            }

            Section(localizedString("Termux: status")) {
                Text((if (termuxInstalled) "✓" else "✗") + localizedString(" Termux installed"))
                Text((if (termuxApi) "✓" else "✗") + localizedString(" Termux:API installed (photo, location, clipboard, SMS)"))
                Text((if (termuxPerm) "✓" else "✗") + localizedString(" “Run commands in Termux” permission"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (termuxInstalled && !termuxPerm) Button(onClick = { permLauncher.launch(Termux.PERMISSION) }) { Text(localizedString("Grant permission")) }
                    if (termuxInstalled && termuxPerm) Button(onClick = {
                        testOutput = context.localizedString("Checking…")
                        scope.launch {
                            val r = Termux.run(context, "echo \"Termux OK: \$(uname -m), \$(date)\"; command -v termux-location >/dev/null && echo 'termux-api: есть' || echo 'termux-api: нет (pkg install termux-api)'; command -v curl >/dev/null && echo 'curl: есть' || echo 'curl: нет'", timeoutMs = 30_000)
                            testOutput = r.combined()
                        }
                    }) { Text(localizedString("Check")) }
                }
                testOutput?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }

            Section(localizedString("Termux setup (once)")) {
                Text(localizedString("1. Install Termux and Termux:API from F-Droid (the Google Play version is outdated and won't work)."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(context, "https://f-droid.org/packages/com.termux/") }) { Text("Termux") }
                    OutlinedButton(onClick = { open(context, "https://f-droid.org/packages/com.termux.api/") }) { Text("Termux:API") }
                    TextButton(onClick = { open(context, "https://github.com/termux/termux-app/releases") }) { Text("GitHub") }
                }
                Text(localizedString("2. Open Termux and run (installs termux-api, curl, rclone, storage access and allows external commands):"))
                CodeBox(Termux.SETUP_SCRIPT) { AgentShare.copy(context, Termux.SETUP_SCRIPT); AgentShare.toast(context, context.localizedString("Copied")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        AgentShare.copy(context, Termux.SETUP_SCRIPT)
                        context.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let { context.startActivity(it) }
                            ?: AgentShare.toast(context, context.localizedString("Termux is not installed"))
                    }) { Text(localizedString("Copy and open Termux")) }
                }
                Text(localizedString("3. Come back here, tap “Grant permission”, then “Check”. 4. Turn on the bridge above."), style = MaterialTheme.typography.bodySmall)
            }

            Section(localizedString("Allowed without asking")) {
                PhoneTool.entries.filterNot { it.screenControl }.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title)
                            if (t.risky) Text(localizedString("better to confirm"), style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = t.wire in auto, onCheckedChange = { v ->
                            PhoneBridgeSettings.setAutoAllowed(context, t.wire, v); auto = PhoneBridgeSettings.autoAllowed(context)
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedString("Let the agent request SMS"), Modifier.weight(1f))
                    Switch(checked = sms, onCheckedChange = { sms = it; PhoneBridgeSettings.setSmsEnabled(context, it) })
                }
            }

            if (ui.log.isNotEmpty()) {
                Section(localizedString("Recent requests")) {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    ui.log.take(15).forEach { e ->
                        Text("${fmt.format(Date(e.time))}  ${PhoneTool.of(e.tool)?.title ?: e.tool} — ${e.state}", fontSize = 12.sp)
                        if (e.summary.isNotBlank()) Text(e.summary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun open(context: android.content.Context, url: String) =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveRoute(server: HttpUrl, cookieJar: CookieJar, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(context.localizedString("Checking…")) }
    var configured by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val authCmd = "pkg install -y rclone >/dev/null 2>&1; rclone authorize \"drive\" 2>&1 | tee \$HOME/.agent-drive-auth.txt"

    suspend fun sendToken(text: String) {
        busy = true
        status = context.localizedString("Sending token to server…")
        status = runCatching {
            val body = buildJsonObject { put("token", JsonPrimitive(text)) }.toString()
            val resp = withContext(Dispatchers.IO) { postJson(cookieJar, server.newBuilder().encodedPath("/phone/api/drive-token").build(), body) }
            val o = Json.parseToJsonElement(resp).jsonObject
            if (o["ok"].toString() == "true") context.localizedString("✓ Google Drive connected to the agent") else context.localizedString("✗ Error: ") + o["error"]
        }.getOrElse { "✗ ${it.message}" }
        busy = false
        refresh++
    }

    LaunchedEffect(refresh) {
        runCatching {
            val s = withContext(Dispatchers.IO) { getJson(cookieJar, server.newBuilder().encodedPath("/phone/api/drive-status").build()) }
            val o = Json.parseToJsonElement(s).jsonObject
            configured = o["configured"].toString() == "true"
            if (!busy) status = if (configured) context.localizedString("✓ Agent is connected to Google Drive (remote gdrive:)") else context.localizedString("The agent is not connected to Google Drive yet")
        }.onFailure { if (!busy) status = tr("Не удалось узнать статус: ${it.message}", "Could not get status: ${it.message}") }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(localizedString("Google Drive")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), localizedString("Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), localizedString("Update")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(localizedString("Status")) { Text(status) }
            Section(localizedString("Saving from the app")) {
                Text(localizedString("Any message, conversation (Markdown/PDF), workspace file or agent screenshot can be sent to Google Drive with “To Google Drive” — the Drive app opens, no login needed."),
                    style = MaterialTheme.typography.bodySmall)
            }
            Section(localizedString("Agent access to Drive (rclone)")) {
                Text(localizedString("The agent on the server works with your Drive via rclone. One Google authorization is needed — your password is never sent, only the rclone token goes to the server."),
                    style = MaterialTheme.typography.bodySmall)
                Text(localizedString("Option A — on this phone (needs Termux with permission, see “Phone & Termux”):"), fontWeight = FontWeight.Medium)
                Button(enabled = !busy, onClick = {
                    if (!Termux.isInstalled(context) || !Termux.hasPermission(context)) {
                        AgentShare.toast(context, context.localizedString("Set up Termux in “Phone” first"))
                    } else {
                        Termux.runVisible(context, authCmd)
                        status = context.localizedString("Termux will open a Google link. Sign in, allow access, then come back and tap “Send token”.")
                    }
                }) { Text(localizedString("1. Sign in to Google via Termux")) }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        val r = Termux.run(context, "cat \$HOME/.agent-drive-auth.txt", timeoutMs = 30_000)
                        if (!r.stdout.contains("{")) status = context.localizedString("Token not found: finish signing in in Termux (step 1).") else sendToken(r.stdout)
                    }
                }) { Text(localizedString("2. Send token to the agent")) }
                Text(localizedString("Option B — on a computer: install rclone and run"), fontWeight = FontWeight.Medium)
                CodeBox("rclone authorize \"drive\"") { AgentShare.copy(context, "rclone authorize \"drive\""); AgentShare.toast(context, context.localizedString("Copied")) }
                Text(localizedString("Copy the whole {…} block between “Paste the following” and “End paste” and paste it here:"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(localizedString("rclone token {…}")) }, minLines = 2, maxLines = 5)
                Button(enabled = !busy && token.contains("{"), onClick = { scope.launch { sendToken(token); token = "" } }) { Text(localizedString("Send token")) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun localizedStringFormat(template: String, vararg args: Any?): String =
    localizedString(template).format(*args)

