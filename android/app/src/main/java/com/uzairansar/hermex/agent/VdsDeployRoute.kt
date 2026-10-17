package com.uzairansar.hermex.agent

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uzairansar.hermex.R
import com.uzairansar.hermex.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeployPhase { Form, Probing, Confirm, Installing, Pairing, Done, Failed }

data class DeployUi(
    val phase: DeployPhase = DeployPhase.Form,
    val probe: VdsProbe? = null,
    val step: DeployEvent.Step? = null,
    val log: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val server: String? = null,
)

class VdsDeployViewModel(
    private val auth: AuthRepository,
    private val loadScript: () -> ByteArray,
    private val deployer: VdsDeployer = VdsDeployer(),
) : ViewModel() {
    private val _ui = MutableStateFlow(DeployUi())
    val ui: StateFlow<DeployUi> = _ui
    private var creds: VdsCredentials? = null
    private var options = VdsOptions()
    private var job: Job? = null

    fun check(c: VdsCredentials, o: VdsOptions) {
        DeployProtocol.validate(c, o)?.let { msg -> c.wipe(); _ui.update { it.copy(phase = DeployPhase.Failed, error = msg) }; return }
        creds?.wipe()
        creds = c
        options = o
        _ui.value = DeployUi(phase = DeployPhase.Probing)
        job = viewModelScope.launch {
            try {
                val p = deployer.probe(c)
                _ui.update { it.copy(phase = DeployPhase.Confirm, probe = p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(DeployProtocol.explain(e))
            }
        }
    }

    fun confirm(update: Boolean) {
        val c = creds ?: return fail(tr("Данные входа уже стёрты — введите заново", "Credentials were wiped — enter them again"))
        val p = _ui.value.probe ?: return
        options = options.copy(update = update)
        _ui.update { it.copy(phase = DeployPhase.Installing, log = emptyList(), warnings = emptyList()) }
        job = viewModelScope.launch {
            try {
                val result = deployer.install(c, p, loadScript(), options) { ev -> onEvent(ev) }
                wipe() // пароль больше не нужен
                _ui.update { it.copy(phase = DeployPhase.Pairing, server = result.server) }
                auth.pairWithCode(result.server, result.code)
                _ui.update { it.copy(phase = DeployPhase.Done) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(if (e is VdsDeployException) e.message.orEmpty() else DeployProtocol.explain(e))
            }
        }
    }

    private fun onEvent(ev: DeployEvent) = _ui.update { s ->
        when (ev) {
            is DeployEvent.Step -> s.copy(step = ev, log = (s.log + "▶ ${ev.n}/${ev.total} ${ev.title}").takeLast(300))
            is DeployEvent.Line -> s.copy(log = (s.log + ev.text).takeLast(300))
            is DeployEvent.Warn -> s.copy(warnings = s.warnings + ev.text, log = (s.log + "⚠ ${ev.text}").takeLast(300))
            is DeployEvent.Failed -> s.copy(log = (s.log + "✗ ${ev.text}").takeLast(300))
            is DeployEvent.Done -> s
        }
    }

    private fun fail(msg: String) {
        wipe()
        _ui.update { it.copy(phase = DeployPhase.Failed, error = msg) }
    }

    fun cancel() {
        job?.cancel()
        wipe()
        _ui.value = DeployUi()
    }

    fun reset() { wipe(); _ui.value = DeployUi() }

    private fun wipe() { creds?.wipe(); creds = null }

    override fun onCleared() { wipe() }
}

private fun loadInstaller(context: Context): ByteArray = context.assets.open("murf-install.sh").use { it.readBytes() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VdsDeployRoute(auth: AuthRepository, onBack: () -> Unit, onConnected: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: VdsDeployViewModel = viewModel(factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VdsDeployViewModel(auth, { loadInstaller(context) }) as T
        }
    })
    val ui by vm.ui.collectAsState()
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("root") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    // секреты — только remember (не rememberSaveable): не попадают в saved state
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var update by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = ""; key = ""; passphrase = "" } }
    LaunchedEffect(ui.phase) {
        if (ui.phase == DeployPhase.Installing || ui.phase == DeployPhase.Done) { password = ""; key = ""; passphrase = "" }
    }
    val busy = ui.phase == DeployPhase.Probing || ui.phase == DeployPhase.Installing || ui.phase == DeployPhase.Pairing

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Свой сервер (VDS)", "Own server (VDS)")) },
            navigationIcon = { IconButton(onClick = { if (!busy) onBack() }) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (ui.phase) {
                DeployPhase.Form, DeployPhase.Probing, DeployPhase.Confirm, DeployPhase.Failed -> {
                    Text(tr("MURF установит на ваш VDS Hermes Agent, hermes-webui, nginx с HTTPS (Let's Encrypt), службы systemd и relay для входа по QR, а затем сам подключится. Нужен чистый Ubuntu 22.04/24.04 или Debian 12+, от 2 ГБ памяти и 5 ГБ диска.",
                        "MURF installs Hermes Agent, hermes-webui, nginx with HTTPS (Let's Encrypt), systemd services and the QR sign-in relay on your VDS, then connects by itself. Needs a clean Ubuntu 22.04/24.04 or Debian 12+, 2 GB RAM and 5 GB disk."),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr("Адрес (IP или имя)", "Address (IP or host)")) }, singleLine = true, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text(tr("Порт", "Port")) }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(user, { user = it.trim() }, Modifier.weight(2f), label = { Text(tr("Пользователь", "User")) }, singleLine = true, enabled = !busy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useKey, onClick = { useKey = false }, label = { Text(tr("Пароль", "Password")) }, enabled = !busy)
                        FilterChip(selected = useKey, onClick = { useKey = true }, label = { Text(tr("Ключ SSH", "SSH key")) }, enabled = !busy)
                    }
                    if (!useKey || user != "root") {
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                            label = { Text(if (useKey) tr("Пароль sudo", "sudo password") else tr("Пароль SSH", "SSH password")) },
                            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                    if (useKey) {
                        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            label = { Text(tr("Закрытый ключ (OpenSSH/PEM, RSA или ECDSA)", "Private key (OpenSSH/PEM, RSA or ECDSA)")) },
                            enabled = !busy, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text(tr("Пароль ключа (если есть)", "Key passphrase (if any)")) },
                            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                    }
                    OutlinedTextField(domain, { domain = it.trim().lowercase() }, Modifier.fillMaxWidth(),
                        label = { Text(tr("Домен (необязательно)", "Domain (optional)")) }, singleLine = true, enabled = !busy,
                        supportingText = { Text(tr("A-запись домена должна указывать на сервер. Без домена — <ip>.sslip.io.", "The domain's A record must point to the server. Without one — <ip>.sslip.io.")) })
                    OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr("E-mail для Let's Encrypt (необязательно)", "E-mail for Let's Encrypt (optional)")) },
                        singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Text(tr("Пароль и ключ используются только для этой установки, не сохраняются и стираются из памяти после неё.", "The password and key are used only for this installation, never stored, and wiped from memory afterwards."),
                        style = MaterialTheme.typography.bodySmall)
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !busy && host.isNotBlank(), onClick = {
                        val c = VdsCredentials(
                            host = host, port = port.toIntOrNull() ?: 22, user = user.ifBlank { "root" },
                            password = password.takeIf { it.isNotEmpty() }?.toCharArray(),
                            privateKey = key.takeIf { useKey && it.isNotBlank() }?.toCharArray(),
                            keyPassphrase = passphrase.takeIf { useKey && it.isNotEmpty() }?.toCharArray(),
                        )
                        vm.check(c, VdsOptions(domain = domain, email = email))
                    }) {
                        if (ui.phase == DeployPhase.Probing) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text(tr("Проверить сервер", "Check server"))
                    }
                }
                DeployPhase.Installing, DeployPhase.Pairing, DeployPhase.Done -> {
                    val st = ui.step
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when (ui.phase) {
                                    DeployPhase.Pairing -> tr("Подключаюсь к новому серверу…", "Connecting to the new server…")
                                    DeployPhase.Done -> tr("✓ Готово: сервер установлен и подключён", "✓ Done: server installed and connected")
                                    else -> st?.let { "${it.n}/${it.total}: ${it.title}" } ?: tr("Запуск…", "Starting…")
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (ui.phase == DeployPhase.Done) {
                                Text(ui.server.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Text(tr("Осталось выбрать модель и ключ провайдера: откройте WebUI в браузере или «Настройки → Провайдеры».", "One step left: pick a model and provider key in WebUI or Settings → Providers."),
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = onConnected) { Text(tr("Открыть чаты", "Open chats")) }
                            } else {
                                LinearProgressIndicator(
                                    progress = { st?.let { (it.n - 1).coerceAtLeast(0).toFloat() / it.total } ?: 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(tr("Установка Hermes Agent занимает 3–10 минут. Если связь прервётся, установка на сервере продолжится, а MURF переподключится.", "Installing Hermes Agent takes 3–10 minutes. If the connection drops, the server keeps installing and MURF reconnects."),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    ui.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(ui.log.takeLast(60).joinToString("\n"), Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    if (ui.phase == DeployPhase.Installing) OutlinedButton(onClick = vm::cancel) { Text(tr("Прекратить наблюдение", "Stop watching")) }
                }
            }
            if (ui.phase == DeployPhase.Failed && ui.log.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(ui.log.takeLast(40).joinToString("\n"), Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }

    if (ui.phase == DeployPhase.Confirm) {
        val p = ui.probe!!
        AlertDialog(
            onDismissRequest = vm::reset,
            title = { Text(tr("Установить на ${host}?", "Install on ${host}?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Ключ сервера (сверьте с панелью хостинга):", "Server key (compare with your hosting panel):"), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("${p.keyType}\n${p.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    Text("${p.os} · ${p.memMb} MB RAM · ${p.freeMb} MB " + tr("свободно", "free"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("Будут установлены пакеты (git, python3, nginx, certbot), создан пользователь «murf», службы murf-webui и murf-relay, сайт nginx" + (if (domain.isBlank()) " для <ip>.sslip.io" else " для $domain") + ". Существующие сайты nginx не изменяются.",
                        "Packages (git, python3, nginx, certbot), user “murf”, services murf-webui and murf-relay and an nginx site" + (if (domain.isBlank()) " for <ip>.sslip.io" else " for $domain") + " will be installed. Existing nginx sites are not changed."),
                        style = MaterialTheme.typography.bodySmall)
                    if (p.freeMb in 1..4999) Text(tr("⚠ Мало места на диске", "⚠ Low disk space"), color = MaterialTheme.colorScheme.error)
                    if (p.alreadyInstalled) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(update, { update = it })
                        Text(tr("MURF уже установлен — обновить Hermes и WebUI", "MURF is already installed — update Hermes and WebUI"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirm(update) }) { Text(tr("Установить", "Install")) } },
            dismissButton = { TextButton(onClick = vm::reset) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
