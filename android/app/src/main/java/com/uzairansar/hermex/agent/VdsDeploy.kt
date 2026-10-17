package com.uzairansar.hermex.agent

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Arrays

/**
 * Развёртывание MURF на VDS по SSH (JSch, BSD-лицензия).
 * Пароль/ключ живут только в памяти на время установки и затираются в [VdsCredentials.wipe].
 */
class VdsCredentials(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    password: CharArray? = null,
    privateKey: CharArray? = null,
    keyPassphrase: CharArray? = null,
) {
    private var passwordBytes: ByteArray? = password?.let { String(it).toByteArray(Charsets.UTF_8) }.also { password?.fill('\u0000') }
    private var keyBytes: ByteArray? = privateKey?.let { String(it).toByteArray(Charsets.UTF_8) }.also { privateKey?.fill('\u0000') }
    private var passphraseBytes: ByteArray? = keyPassphrase?.let { String(it).toByteArray(Charsets.UTF_8) }.also { keyPassphrase?.fill('\u0000') }

    val hasPassword get() = passwordBytes?.isNotEmpty() == true
    val hasKey get() = keyBytes?.isNotEmpty() == true

    internal fun password(): ByteArray? = passwordBytes?.copyOf()
    internal fun key(): ByteArray? = keyBytes?.copyOf()
    internal fun passphrase(): ByteArray? = passphraseBytes?.copyOf()

    fun wipe() {
        passwordBytes?.let { Arrays.fill(it, 0) }; passwordBytes = null
        keyBytes?.let { Arrays.fill(it, 0) }; keyBytes = null
        passphraseBytes?.let { Arrays.fill(it, 0) }; passphraseBytes = null
    }
}

data class VdsOptions(val domain: String = "", val email: String = "", val noTls: Boolean = false, val update: Boolean = false)

data class VdsProbe(
    val fingerprint: String,
    val keyType: String,
    val isRoot: Boolean,
    val os: String,
    val freeMb: Long,
    val memMb: Long,
    val alreadyInstalled: Boolean,
)

sealed interface DeployEvent {
    data class Step(val n: Int, val total: Int, val title: String) : DeployEvent
    data class Line(val text: String) : DeployEvent
    data class Warn(val text: String) : DeployEvent
    data class Failed(val text: String) : DeployEvent
    data class Done(val result: DeployResult) : DeployEvent
}

@Serializable
data class DeployResult(
    val ok: Boolean = false,
    val code: String = "",
    val server: String = "",
    val payload: String = "",
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Int = 0,
)

class VdsDeployException(message: String, cause: Throwable? = null) : IOException(message, cause)

object DeployProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /** Разбор строки вывода установщика (протокол MURF:STEP/WARN/ERROR/DONE). */
    fun parse(line: String): DeployEvent {
        val text = line.trimEnd('\r')
        return when {
            text.startsWith("MURF:STEP ") -> {
                val parts = text.removePrefix("MURF:STEP ").split(' ', limit = 3)
                val n = parts.getOrNull(0)?.toIntOrNull()
                val total = parts.getOrNull(1)?.toIntOrNull()
                if (n != null && total != null) DeployEvent.Step(n, total, parts.getOrNull(2).orEmpty()) else DeployEvent.Line(text)
            }
            text.startsWith("MURF:WARN ") -> DeployEvent.Warn(text.removePrefix("MURF:WARN "))
            text.startsWith("MURF:ERROR ") -> DeployEvent.Failed(text.removePrefix("MURF:ERROR "))
            text.startsWith("MURF:DONE ") -> runCatching { json.decodeFromString<DeployResult>(text.removePrefix("MURF:DONE ")) }
                .getOrNull()?.takeIf { it.ok && it.code.isNotBlank() }?.let { DeployEvent.Done(it) }
                ?: DeployEvent.Failed(tr("установщик вернул непонятный результат", "installer returned an unreadable result"))
            else -> DeployEvent.Line(text)
        }
    }

    /** Аргументы установщика; всё проверяется на сервере ещё раз. */
    fun installerArgs(o: VdsOptions): List<String> = buildList {
        if (o.domain.isNotBlank()) { add("--domain"); add(o.domain.trim()) }
        if (o.email.isNotBlank()) { add("--email"); add(o.email.trim()) }
        if (o.noTls) add("--no-tls")
        if (o.update) add("--update")
    }

    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+$")
    private val IPV6 = Regex("^[0-9A-Fa-f:]{2,39}$")
    private val EMAIL = Regex("^[^@\\s'\"]+@[^@\\s'\"]+\\.[^@\\s'\"]+$")
    private val USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")

    fun validate(c: VdsCredentials, o: VdsOptions): String? = when {
        !(HOST.matches(c.host) || IPV6.matches(c.host)) -> tr("Неверный адрес сервера", "Invalid server address")
        c.port !in 1..65535 -> tr("Неверный порт", "Invalid port")
        !USER.matches(c.user) -> tr("Неверное имя пользователя", "Invalid user name")
        !c.hasPassword && !c.hasKey -> tr("Нужен пароль или ключ SSH", "SSH password or key is required")
        o.domain.isNotBlank() && !HOST.matches(o.domain.trim()) -> tr("Неверный домен", "Invalid domain")
        o.email.isNotBlank() && !EMAIL.matches(o.email.trim()) -> tr("Неверный e-mail", "Invalid e-mail")
        else -> null
    }

    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Понятный текст для типичных ошибок SSH. */
    fun explain(e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.toList()
        val msg = e.message.orEmpty()
        return when {
            cause.any { it is UnknownHostException } -> tr("Сервер не найден (DNS). Проверьте адрес.", "Server not found (DNS). Check the address.")
            cause.any { it is ConnectException || it is NoRouteToHostException } -> tr("Нет соединения с SSH-портом. Проверьте адрес, порт и что сервер включён.", "Can't reach the SSH port. Check the address, port and that the server is on.")
            cause.any { it is SocketTimeoutException } || msg.contains("timeout", true) -> tr("Сервер не отвечает (таймаут).", "The server doesn't respond (timeout).")
            msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) -> tr("SSH не принял пароль или ключ.", "SSH rejected the password or key.")
            msg.contains("invalid privatekey", true) -> tr("Ключ не распознан (поддерживаются RSA и ECDSA в формате OpenSSH/PEM).", "Key not recognized (RSA and ECDSA in OpenSSH/PEM format are supported).")
            msg.contains("HostKey has been changed", true) || msg.contains("reject HostKey", true) -> tr("Ключ сервера изменился после проверки — установка остановлена.", "The server key changed after the check — installation stopped.")
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }
}

class VdsDeployer(private val connectTimeoutMs: Int = 15_000) {

    /** Хранилище ключей хоста только в памяти: либо «запомнить первый», либо «пустить только этот». */
    private class PinnedHostKeys(private val pinned: String?) : HostKeyRepository {
        var seen: HostKey? = null
        override fun check(host: String?, key: ByteArray?): Int {
            val fp = key?.let { sha256Fingerprint(it) }
            if (pinned == null) return HostKeyRepository.OK
            return if (fp == pinned) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) { seen = hostkey }
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "murf-memory"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private fun open(c: VdsCredentials, pinnedFingerprint: String?): Session {
        val jsch = JSch()
        c.key()?.let { key ->
            val pass = c.passphrase()
            try { jsch.addIdentity("murf-key", key, null, pass) } finally { Arrays.fill(key, 0); pass?.let { Arrays.fill(it, 0) } }
        }
        val repo = PinnedHostKeys(pinnedFingerprint)
        jsch.hostKeyRepository = repo
        val session = jsch.getSession(c.user, c.host, c.port)
        c.password()?.let { session.setPassword(it) }
        // первый вход — показываем отпечаток пользователю; дальше пускаем только подтверждённый ключ
        session.setConfig("StrictHostKeyChecking", if (pinnedFingerprint == null) "no" else "yes")
        session.setConfig("PreferredAuthentications", if (c.hasKey) "publickey,password,keyboard-interactive" else "password,keyboard-interactive")
        session.serverAliveInterval = 15_000
        session.serverAliveCountMax = 4
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = c.password()?.let { String(it, Charsets.UTF_8) }
            override fun promptPassword(message: String?) = c.hasPassword
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = pinnedFingerprint == null
            override fun showMessage(message: String?) = Unit
        }
        session.connect(connectTimeoutMs)
        return session
    }

    /** Выполнить команду; stdin — опционально (например, пароль для sudo -S). */
    private fun exec(session: Session, command: String, stdin: ByteArray? = null, onLine: ((String) -> Unit)? = null): Pair<Int, String> {
        val ch = session.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        val out = ch.inputStream
        val input = ch.outputStream
        ch.connect(connectTimeoutMs)
        if (stdin != null) { input.write(stdin); input.flush() }
        input.close()
        val collected = StringBuilder()
        out.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (onLine != null) onLine(line) else if (collected.length < 64_000) collected.append(line).append('\n')
            }
        }
        while (!ch.isClosed) Thread.sleep(50)
        val status = ch.exitStatus
        ch.disconnect()
        if (collected.isEmpty() && err.size() > 0 && onLine == null) collected.append(err.toString(Charsets.UTF_8.name()).take(4000))
        return status to collected.toString()
    }

    private fun sudoWrap(c: VdsCredentials, isRoot: Boolean, command: String): Pair<String, ByteArray?> =
        if (isRoot) command to null
        else "sudo -S -p '' sh -c ${DeployProtocol.shellQuote(command)}" to (c.password()?.plus('\n'.code.toByte()) ?: ByteArray(0))

    suspend fun probe(c: VdsCredentials): VdsProbe = withContext(Dispatchers.IO) {
        val session = try { open(c, null) } catch (e: JSchException) { throw VdsDeployException(DeployProtocol.explain(e), e) }
        try {
            val hk = session.hostKey
            val fp = sha256Fingerprint(java.util.Base64.getDecoder().decode(hk.key))
            val (_, text) = exec(
                session,
                "id -u; . /etc/os-release 2>/dev/null; echo \"\${PRETTY_NAME:-unknown}\"; df -Pm / | awk 'NR==2{print \$4}'; " +
                    "awk '/MemTotal/{print int(\$2/1024)}' /proc/meminfo; test -f /etc/systemd/system/murf-relay.service && echo installed || echo fresh",
            )
            val lines = text.lines()
            val isRoot = lines.getOrNull(0)?.trim() == "0"
            if (!isRoot) {
                if (!c.hasPassword) throw VdsDeployException(tr("Пользователь не root: для sudo нужен пароль.", "User is not root: sudo needs a password."))
                val (status, _) = exec(session, "sudo -S -p '' true", c.password()?.plus('\n'.code.toByte()))
                if (status != 0) throw VdsDeployException(tr("У пользователя нет прав sudo (или неверный пароль).", "The user has no sudo rights (or wrong password)."))
            }
            VdsProbe(
                fingerprint = fp,
                keyType = hk.type,
                isRoot = isRoot,
                os = lines.getOrNull(1).orEmpty(),
                freeMb = lines.getOrNull(2)?.trim()?.toLongOrNull() ?: 0,
                memMb = lines.getOrNull(3)?.trim()?.toLongOrNull() ?: 0,
                alreadyInstalled = lines.getOrNull(4)?.trim() == "installed",
            )
        } finally {
            session.disconnect()
        }
    }

    /**
     * Загрузить установщик и запустить его в фоне на сервере (nohup), затем читать журнал.
     * Если SSH оборвётся — переподключаемся и дочитываем журнал (установка на сервере не прерывается).
     */
    suspend fun install(
        c: VdsCredentials,
        probe: VdsProbe,
        script: ByteArray,
        options: VdsOptions,
        onEvent: (DeployEvent) -> Unit,
    ): DeployResult = withContext(Dispatchers.IO) {
        val args = DeployProtocol.installerArgs(options).joinToString(" ") { DeployProtocol.shellQuote(it) }
        val remote = "/root/murf-install.sh"
        val log = "/var/log/murf-install.log"
        var session = try { open(c, probe.fingerprint) } catch (e: JSchException) { throw VdsDeployException(DeployProtocol.explain(e), e) }
        try {
            onEvent(DeployEvent.Line(tr("Загружаю установщик…", "Uploading the installer…")))
            val tmp = "/tmp/murf-install-${System.currentTimeMillis()}.sh"
            val (upStatus, upText) = exec(session, "umask 077; cat > $tmp", script)
            if (upStatus != 0) throw VdsDeployException(tr("Не удалось загрузить установщик: ", "Could not upload the installer: ") + upText.take(300))
            val start = "install -m 700 $tmp $remote && rm -f $tmp && : > $log && chmod 600 $log && " +
                "(nohup setsid bash $remote $args > $log 2>&1 < /dev/null & echo \$!)"
            val (cmd, stdin) = sudoWrap(c, probe.isRoot, start)
            val (st, pidText) = exec(session, cmd, stdin)
            val pid = pidText.lines().lastOrNull { it.trim().toIntOrNull() != null }?.trim()
            if (st != 0 || pid == null) throw VdsDeployException(tr("Не удалось запустить установщик: ", "Could not start the installer: ") + pidText.take(300))
            onEvent(DeployEvent.Line(tr("Установщик запущен на сервере (PID $pid)", "Installer started on the server (PID $pid)")))

            var linesSeen = 0
            var result: DeployResult? = null
            var failure: String? = null
            var reconnects = 0
            while (result == null && failure == null) {
                currentCoroutineContext().ensureActive()
                try {
                    val tail = "tail -n +${linesSeen + 1} --pid=$pid -F $log 2>/dev/null"
                    val (tcmd, tstdin) = sudoWrap(c, probe.isRoot, tail)
                    exec(session, tcmd, tstdin) { line ->
                        linesSeen++
                        when (val ev = DeployProtocol.parse(line)) {
                            is DeployEvent.Done -> { result = ev.result; onEvent(DeployEvent.Line(tr("Установка завершена", "Installation finished"))) }
                            is DeployEvent.Failed -> { failure = ev.text; onEvent(ev) }
                            else -> onEvent(ev)
                        }
                    }
                    if (result == null && failure == null) {
                        // tail закончился: процесс завершился без DONE — дочитываем хвост журнала
                        val (rcmd, rstdin) = sudoWrap(c, probe.isRoot, "tail -n +${linesSeen + 1} $log")
                        val (_, rest) = exec(session, rcmd, rstdin)
                        rest.lines().filter { it.isNotBlank() }.forEach { line ->
                            linesSeen++
                            when (val ev = DeployProtocol.parse(line)) {
                                is DeployEvent.Done -> result = ev.result
                                is DeployEvent.Failed -> { failure = ev.text; onEvent(ev) }
                                else -> onEvent(ev)
                            }
                        }
                        if (result == null && failure == null) failure = tr("установщик завершился без результата (журнал: $log)", "installer exited without a result (log: $log)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++reconnects > 20) throw VdsDeployException(tr("Связь с сервером потеряна: ", "Lost connection to the server: ") + DeployProtocol.explain(e), e)
                    onEvent(DeployEvent.Warn(tr("Связь прервалась, переподключаюсь… (установка на сервере продолжается)", "Connection dropped, reconnecting… (installation continues on the server)")))
                    runCatching { session.disconnect() }
                    delay(3_000)
                    session = try { open(c, probe.fingerprint) } catch (je: JSchException) {
                        if (je.message.orEmpty().contains("HostKey", true)) throw VdsDeployException(DeployProtocol.explain(je), je)
                        continue
                    }
                }
            }
            failure?.let { throw VdsDeployException(it) }
            result!!
        } finally {
            runCatching { session.disconnect() }
        }
    }

    companion object {
        fun sha256Fingerprint(key: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
