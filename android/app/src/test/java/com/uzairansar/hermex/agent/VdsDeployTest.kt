package com.uzairansar.hermex.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VdsDeployTest {
    @Test
    fun parsesInstallerProtocol() {
        assertEquals(DeployEvent.Step(3, 10, "Пользователь murf"), DeployProtocol.parse("MURF:STEP 3 10 Пользователь murf"))
        assertEquals(DeployEvent.Warn("мало памяти"), DeployProtocol.parse("MURF:WARN мало памяти"))
        assertEquals(DeployEvent.Failed("нет места"), DeployProtocol.parse("MURF:ERROR нет места\r"))
        assertEquals(DeployEvent.Line("обычная строка"), DeployProtocol.parse("обычная строка"))
        val done = DeployProtocol.parse("""MURF:DONE {"ok": true, "code": "ABCDEFGHIJKLMNOPQRST", "expires_in": 300, "server": "https://1-2-3-4.sslip.io", "payload": "murf://pair?v=1"}""")
        assertTrue(done is DeployEvent.Done)
        assertEquals("https://1-2-3-4.sslip.io", (done as DeployEvent.Done).result.server)
        assertTrue(DeployProtocol.parse("MURF:DONE {broken") is DeployEvent.Failed)
        assertTrue(DeployProtocol.parse("""MURF:DONE {"ok": false}""") is DeployEvent.Failed)
    }

    @Test
    fun validatesInputsAndQuotesArguments() {
        fun creds(host: String = "203.0.113.5", user: String = "root", pw: String? = "x") =
            VdsCredentials(host = host, user = user, password = pw?.toCharArray())
        assertNull(DeployProtocol.validate(creds(), VdsOptions()))
        assertNull(DeployProtocol.validate(creds(host = "vps.example.com"), VdsOptions(domain = "murf.example.com", email = "a@b.co")))
        assertNotNull(DeployProtocol.validate(creds(host = "bad host;rm -rf"), VdsOptions()))
        assertNotNull(DeployProtocol.validate(creds(user = "root;id"), VdsOptions()))
        assertNotNull(DeployProtocol.validate(creds(pw = null), VdsOptions()))
        assertNotNull(DeployProtocol.validate(creds(), VdsOptions(domain = "x.com\$(id)")))
        assertNotNull(DeployProtocol.validate(creds(), VdsOptions(email = "a'@b.co")))
        assertEquals(listOf("--domain", "a.example", "--email", "me@a.example", "--update"),
            DeployProtocol.installerArgs(VdsOptions(domain = " a.example ", email = "me@a.example", update = true)))
        assertEquals("'it'\\''s'", DeployProtocol.shellQuote("it's"))
    }

    @Test
    fun credentialsAreWipedFromMemory() {
        val pw = "secret".toCharArray()
        val c = VdsCredentials(host = "h.example", password = pw)
        assertTrue(pw.all { it == '\u0000' }) // исходный массив затёрт сразу
        assertTrue(c.hasPassword)
        c.wipe()
        assertTrue(!c.hasPassword && c.password() == null)
    }

    @Test
    fun fingerprintMatchesOpenSshFormat() {
        // ssh-keygen -lf: SHA256 от сырого blob ключа в base64 без '='
        val fp = VdsDeployer.sha256Fingerprint(byteArrayOf(1, 2, 3))
        assertTrue(fp.startsWith("SHA256:") && !fp.endsWith("="))
    }

    /**
     * Полная установка через SSH на ЧИСТЫЙ тестовый контейнер (не на реальный сервер!).
     * Запуск: MURF_SSH_TEST=host:port:/path/to/password-file ./gradlew testDebugUnitTest --tests '*VdsDeployTest*'
     */
    @Test
    fun deploysToDisposableContainer() = runBlocking {
        val spec = System.getenv("MURF_SSH_TEST").orEmpty()
        assumeTrue("MURF_SSH_TEST не задан — пропуск", spec.isNotBlank())
        val (host, port, pwFile) = spec.split(':', limit = 3)
        val script = File(System.getProperty("user.dir"), "src/main/assets/murf-install.sh").readBytes()
        val creds = VdsCredentials(host = host, port = port.toInt(), user = "root", password = File(pwFile).readText().trim().toCharArray())
        val deployer = VdsDeployer()
        val probe = deployer.probe(creds)
        println("probe: $probe")
        val events = mutableListOf<DeployEvent>()
        val result = deployer.install(creds, probe, script, VdsOptions(domain = "murf.test", noTls = true)) { ev ->
            events += ev
            println(ev)
        }
        creds.wipe()
        assertTrue(result.ok && result.code.isNotBlank())
        assertEquals("http://murf.test", result.server)
        assertTrue(events.filterIsInstance<DeployEvent.Step>().any { it.n == 10 })
    }
}
