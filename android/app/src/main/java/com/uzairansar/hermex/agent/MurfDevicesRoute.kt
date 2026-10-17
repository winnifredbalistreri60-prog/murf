package com.uzairansar.hermex.agent

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
            title = { Text(tr("Устройства", "Devices")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
            actions = { IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Refresh")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevicesSection(tr("Это устройство", "This device")) {
                Text(
                    if (thisPaired) tr("✓ Привязано по QR: при истечении сессии MURF войдёт сам, пароль не хранится.", "✓ Paired by QR: when the session expires MURF signs in by itself; no password is stored.")
                    else tr("Вход выполнен паролем. Привяжите устройство, чтобы не вводить пароль снова (сессия будет обновляться по токену устройства из Keystore).", "Signed in with a password. Pair this device so you never type it again (the session is renewed with a device token kept in Keystore)."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!thisPaired) Button(enabled = !busy, onClick = {
                    busy = true; info = null; error = null
                    scope.launch {
                        runCatching {
                            val c = api.newCode(server, "self")
                            auth.pairWithCode(server.toString(), c.code)
                        }.onSuccess { info = tr("Устройство привязано", "Device paired") }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                        refresh++
                    }
                }) { Text(tr("Привязать это устройство", "Pair this device")) }
                Text("ID: $myId", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }

            DevicesSection(tr("Подключить другое устройство", "Connect another device")) {
                Text(tr("Покажите QR второму телефону или планшету: MURF → «QR-код» на экране входа. Код одноразовый и живёт 3 минуты. Можно и на сервере: «murf pair».", "Show this QR to another phone or tablet: MURF → “Scan QR” on the sign-in screen. The code is single-use and lives 3 minutes. On the server: `murf pair`."),
                    style = MaterialTheme.typography.bodySmall)
                val c = code
                if (c == null) {
                    Button(enabled = !busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { api.newCode(server, "app") }.onSuccess { code = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            busy = false
                        }
                    }) { Text(tr("Показать QR", "Show QR")) }
                } else {
                    val image = remember(c.payload) { qrImageBitmap(c.payload) }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(image, tr("QR привязки", "Pairing QR"), Modifier.size(260.dp).background(Color.White))
                        SelectionContainer { Text(c.code, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
                        Text(tr("Осталось $secondsLeft с", "$secondsLeft s left"), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { code = null }) { Text(tr("Скрыть", "Hide")) }
                    }
                }
            }

            DevicesSection(tr("Привязанные устройства", "Paired devices")) {
                when {
                    loading -> CircularProgressIndicator(strokeWidth = 2.dp)
                    devices == null -> Text(tr("Список недоступен", "List unavailable"), style = MaterialTheme.typography.bodySmall)
                    devices!!.devices.isEmpty() -> Text(tr("Пока нет устройств, привязанных по QR.", "No QR-paired devices yet."), style = MaterialTheme.typography.bodySmall)
                    else -> devices!!.devices.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name.ifBlank { d.id } + if (d.id == myId) tr(" (это устройство)", " (this device)") else "", fontWeight = FontWeight.Medium)
                                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                Text(tr("последний вход ", "last used ") + fmt.format(Date(d.lastUsed * 1000)) + (if (d.lastIp.isNotBlank()) " · ${d.lastIp}" else ""),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !busy, onClick = { confirmRevoke = d }) { Text(tr("Отозвать", "Revoke")) }
                        }
                    }
                }
                if (devices?.webuiSecretConfigured == false) {
                    Text(tr("⚠ На сервере не задан пароль WebUI для relay: выполните «murf set-webui-password», иначе привязанные устройства не смогут войти.", "⚠ The relay has no WebUI password: run `murf set-webui-password` on the server, otherwise paired devices can't sign in."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(tr("Вход по паролю остаётся запасным способом. Отзыв сразу закрывает сессии устройства.", "Password sign-in stays as a fallback. Revoking closes the device's sessions immediately."),
                style = MaterialTheme.typography.bodySmall)
        }
    }

    confirmRevoke?.let { d ->
        val self = d.id == myId
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(tr("Отозвать доступ?", "Revoke access?")) },
            text = {
                Text(
                    if (self) tr("Это устройство потеряет вход по QR и будет разлогинено.", "This device will lose QR sign-in and will be signed out.")
                    else tr("«${d.name}» больше не сможет войти без нового QR.", "“${d.name}” won't be able to sign in without a new QR."),
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
                                info = tr("Доступ отозван", "Access revoked")
                            }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                        refresh++
                    }
                }) { Text(tr("Отозвать", "Revoke")) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** Ручной ввод: «адрес сервера» + «код» (если камера недоступна). */
@Composable
fun ManualPairDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var server by remember { mutableStateOf("https://") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Код привязки", "Pairing code")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("На сервере выполните «murf pair» и введите адрес и код.", "Run `murf pair` on the server and enter the address and code."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(server, { server = it.trim() }, label = { Text(tr("Адрес сервера", "Server address")) }, singleLine = true)
                OutlinedTextField(code, { code = it.trim().uppercase() }, label = { Text(tr("Код", "Code")) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = code.length >= 16, onClick = { onSubmit("$server $code") }) { Text(tr("Подключить", "Connect")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
