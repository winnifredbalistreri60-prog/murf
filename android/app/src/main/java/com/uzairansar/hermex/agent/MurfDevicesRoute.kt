package com.uzairansar.hermex.agent

import com.uzairansar.hermex.ui.localization.localizedString

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.R
import com.uzairansar.hermex.data.repository.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.text.DateFormat
import java.util.Date

@Composable
private fun DevicesSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** «Устройства»: привязанные по QR телефоны/планшеты, отзыв доступа, QR для нового устройства. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MurfDevicesRoute(
    server: HttpUrl,
    api: MurfPairingApi,
    tokens: DeviceTokenStore,
    auth: AuthRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<PairedDevicesResponse?>(null) }
    var code by remember { mutableStateOf<PairCodeResponse?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var confirmRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    val myId = remember { tokens.deviceId() }
    val thisPaired = remember(refresh) { tokens.hasToken(server) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        devices = runCatching { api.devices(server) }.onFailure { error = it.message ?: it.javaClass.simpleName }.getOrNull()
        loading = false
    }
    LaunchedEffect(code) {
        val c = code ?: return@LaunchedEffect
        secondsLeft = c.expiresIn
        while (secondsLeft > 0) { delay(1000); secondsLeft-- }
        code = null
        refresh++
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(localizedString("Devices")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), localizedString("Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), localizedString("Refresh")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevicesSection(localizedString("This device")) {
                Text(
                    if (thisPaired) localizedString("✓ Paired by QR: when the session expires MURF signs in by itself; no password is stored.")
                    else localizedString("Signed in with a password. Pair this device so you never type it again (the session is renewed with a device token kept in Keystore)."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!thisPaired) Button(enabled = !busy, onClick = {
                    busy = true; info = null; error = null
                    scope.launch {
                        runCatching {
                            val c = api.newCode(server, "self")
                            auth.pairWithCode(server.toString(), c.code)
                        }.onSuccess { info = context.localizedString("Device paired") }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                        refresh++
                    }
                }) { Text(localizedString("Pair this device")) }
                Text("ID: $myId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }

            DevicesSection(localizedString("Connect another device")) {
                Text(localizedString("Show this QR to another phone or tablet: MURF → “Scan QR” on the sign-in screen. The code is single-use and lives 3 minutes. On the server: `murf pair`."),
                    style = MaterialTheme.typography.bodySmall)
                val c = code
                if (c == null) {
                    Button(enabled = !busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { api.newCode(server, "app") }.onSuccess { code = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            busy = false
                        }
                    }) { Text(localizedString("Show QR")) }
                } else {
                    val image = remember(c.payload) { qrImageBitmap(c.payload) }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(image, localizedString("Pairing QR"), Modifier.size(260.dp).background(Color.White))
                        SelectionContainer { Text(c.code, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                        Text(localizedStringFormat("%1\$d s left" , secondsLeft), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { code = null }) { Text(localizedString("Hide")) }
                    }
                }
            }

            DevicesSection(localizedString("Paired devices")) {
                when {
                    loading -> CircularProgressIndicator(strokeWidth = 2.dp)
                    devices == null -> Text(localizedString("List unavailable"), style = MaterialTheme.typography.bodySmall)
                    devices!!.devices.isEmpty() -> Text(localizedString("No QR-paired devices yet."), style = MaterialTheme.typography.bodySmall)
                    else -> devices!!.devices.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name.ifBlank { d.id } + if (d.id == myId) localizedString(" (this device)") else "", fontWeight = FontWeight.Medium)
                                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                Text(localizedString("last used ") + fmt.format(Date(d.lastUsed * 1000)) + (if (d.lastIp.isNotBlank()) " · ${d.lastIp}" else ""),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !busy, onClick = { confirmRevoke = d }) { Text(localizedString("Revoke")) }
                        }
                    }
                }
                if (devices?.webuiSecretConfigured == false) {
                    Text(localizedString("⚠ The relay has no WebUI password: run `murf set-webui-password` on the server, otherwise paired devices can't sign in."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(localizedString("Password sign-in stays as a fallback. Revoking closes the device's sessions immediately."),
                style = MaterialTheme.typography.bodySmall)
        }
    }

    confirmRevoke?.let { d ->
        val self = d.id == myId
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(localizedString("Revoke access?")) },
            text = {
                Text(
                    if (self) localizedString("This device will lose QR sign-in and will be signed out.")
                    else localizedStringFormat("“%1\$s” won't be able to sign in without a new QR." , d.name),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = null
                    busy = true
                    scope.launch {
                        runCatching { api.revoke(server, d.id) }
                            .onSuccess {
                                if (self) tokens.remove(server)
                                info = context.localizedString("Access revoked")
                            }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                        refresh++
                    }
                }) { Text(localizedString("Revoke")) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(localizedString("Cancel")) } },
        )
    }
}

/** Ручной ввод: «адрес сервера» + «код» (если камера недоступна). */
@Composable
fun ManualPairDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var server by remember { mutableStateOf("https://") }
    var code by remember { mutableStateOf("") }
    // MURF Hermes: 16–40; DeepSeek Harness remote: 8 (алфавит без 0/1/I/O) или полная ссылка ?auth= в поле адреса.
    val canSubmit = PairPayloadParser.parse("$server $code") != null ||
        PairPayloadParser.parse(server) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedString("Pairing code")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    localizedString("MURF: `murf pair` + address and code. Harness: a ?auth= link, or address + 8-char code."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(server, { server = it.trim() }, label = { Text(localizedString("Server address")) }, singleLine = true)
                OutlinedTextField(code, { code = it.trim().uppercase() }, label = { Text(localizedString("Code")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val raw = if (server.contains("auth=", ignoreCase = true)) server else "$server $code"
                    onSubmit(raw)
                },
            ) { Text(localizedString("Connect")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localizedString("Cancel")) } },
    )
}

@Composable
private fun localizedStringFormat(template: String, vararg args: Any?): String =
    String.format(java.util.Locale.ROOT, localizedString(template), *args)
