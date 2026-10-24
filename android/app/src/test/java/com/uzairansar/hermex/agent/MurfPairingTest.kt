package com.uzairansar.hermex.agent

import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.core.network.PersistentCookieJar
import com.uzairansar.hermex.data.repository.AuthRepository
import com.uzairansar.hermex.data.repository.AuthState
import com.uzairansar.hermex.data.secure.SecretStore
import com.uzairansar.hermex.data.secure.ServerRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MurfPairingTest {
    @Test
    fun parsesMurfQrPayload() {
        val p = PairPayloadParser.parse("murf://pair?v=1&u=https%3A%2F%2Fagent.example.com&c=ABCDEFGHIJKLMNOPQRST")
        assertNotNull(p)
        assertEquals("https://agent.example.com/", p!!.server.toString())
        assertEquals("ABCDEFGHIJKLMNOPQRST", p.code)
    }

    @Test
    fun parsesDshAuthLink() {
        val https = PairPayloadParser.parse("https://harness.example.com/?auth=AB23EFGH")
        assertNotNull(https)
        assertEquals(PairProtocol.DshRemote, https!!.protocol)
        assertEquals("https://harness.example.com/", https.server.toString())
        assertEquals("AB23EFGH", https.code)

        val lan = PairPayloadParser.parse("http://192.168.6.113:3088/?auth=YZDYTAUH")
        assertNotNull(lan)
        assertEquals(PairProtocol.DshRemote, lan!!.protocol)
        assertEquals("http://192.168.6.113:3088/", lan.server.toString())
        assertEquals("YZDYTAUH", lan.code)
    }

    @Test
    fun parsesDshManualUrlAndCode() {
        val p = PairPayloadParser.parse("https://harness.example.com/  AB23EFGH")
        assertNotNull(p)
        assertEquals(PairProtocol.DshRemote, p!!.protocol)
        assertEquals("AB23EFGH", p.code)
    }

    @Test
    fun rejectsPublicHttpDshLink() {
        assertNull(PairPayloadParser.parse("http://public.example.com/?auth=AB23EFGH"))
    }

    @Test
    fun hermesMurfProtocolUnchanged() {
        val p = PairPayloadParser.parse("murf://pair?v=1&u=https%3A%2F%2Fagent.example.com&c=ABCDEFGHIJKLMNOPQRST")
        assertEquals(PairProtocol.HermesMurf, p!!.protocol)
    }

    @Test
    fun parsesManualServerAndCode() {
        val p = PairPayloadParser.parse("  https://agent.example.com/chat  abcdefghijklmnopqrst ")
        assertEquals("https://agent.example.com/", p!!.server.toString())
        assertEquals("ABCDEFGHIJKLMNOPQRST", p.code)
    }

    @Test
    fun rejectsForeignOrUnsafePayloads() {
        val bad = listOf(
            "https://example.com",                                                   // обычная ссылка
            "murf://pair?v=2&u=https%3A%2F%2Fa.example&c=ABCDEFGHIJKLMNOPQRST",       // неизвестная версия
            "murf://pair?v=1&u=http%3A%2F%2Fpublic.example.com&c=ABCDEFGHIJKLMNOPQRST", // публичный http
            "murf://pair?v=1&u=https%3A%2F%2Fu%3Ap%40a.example&c=ABCDEFGHIJKLMNOPQRST", // логин в URL
            "murf://pair?v=1&u=https%3A%2F%2Fa.example&c=SHORT",                       // короткий код
            "murf://pair?v=1&u=https%3A%2F%2Fa.example&c=ABCDEFGHIJKLMNOPQR01",        // не base32
            "javascript:alert(1) ABCDEFGHIJKLMNOPQRST",
        )
        bad.forEach { assertNull(it, PairPayloadParser.parse(it)) }
    }

    @Test
    fun allowsPlainHttpOnlyForPrivateHosts() {
        assertNotNull(PairPayloadParser.parse("murf://pair?v=1&u=http%3A%2F%2F192.168.1.5%3A8080&c=ABCDEFGHIJKLMNOPQRST"))
    }

    @Test
    fun deviceTokenStoreKeepsTokenPerServerAndStableDeviceId() {
        val store = DeviceTokenStore(MemorySecrets())
        val a = "https://a.example/".toHttpUrl()
        val b = "https://b.example/".toHttpUrl()
        val id = store.deviceId()
        assertEquals(id, store.deviceId())
        assertTrue(id.matches(Regex("a-[0-9a-f]{16}")))
        store.save(a, "tok-a")
        assertTrue(store.hasToken(a))
        assertFalse(store.hasToken(b))
        store.remove(a)
        assertFalse(store.hasToken(a))
    }

    @Test
    fun pairWithCodeClaimsTokenGetsCookieAndLogsIn() = runBlocking {
        val fake = FakeHermes()
        val server = MockWebServer().apply { dispatcher = fake; start() }
        try {
            val env = Env()
            val url = server.url("/").toString()
            env.auth.pairWithCode(url, fake.code)
            val state = env.auth.state.value
            assertTrue(state is AuthState.LoggedIn)
            val base = server.url("/")
            assertTrue(env.tokens.hasToken(base))
            assertTrue(env.jar.loadForRequest(base).any { it.name == "hermes_session" && it.value == "s1" })
            // токен устройства не попадает в cookie и хранится только в зашифрованном хранилище
            assertEquals(1, fake.claims.size)
            assertTrue(fake.claims[0].contains(env.tokens.deviceId()))
            // повторно тот же код не принимается
            try {
                env.auth.pairWithCode(url, fake.code)
                fail("одноразовый код принят повторно")
            } catch (_: Exception) {
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun expiredSessionIsRenewedSilentlyWithDeviceToken() = runBlocking {
        val fake = FakeHermes()
        val server = MockWebServer().apply { dispatcher = fake; start() }
        try {
            val env = Env()
            val base = server.url("/")
            env.auth.pairWithCode(base.toString(), fake.code)
            fake.validSessions.clear() // сервер «забыл» сессию (перезапуск WebUI / истёк срок)
            env.auth.handleUnauthorized(base, env.auth.currentAuthGeneration(base))
            assertTrue("должны остаться в системе", env.auth.state.value is AuthState.LoggedIn)
            assertTrue(env.jar.loadForRequest(base).any { it.value == "s2" })
            // отозванный токен: выходим и забываем токен
            fake.revoked = true
            fake.validSessions.clear()
            env.auth.handleUnauthorized(base, env.auth.currentAuthGeneration(base))
            assertTrue(env.auth.state.value is AuthState.LoggedOut)
            assertFalse(env.tokens.hasToken(base))
        } finally {
            server.close()
        }
    }


    @Test
    fun pairWithDshAuthStoresSessionCookie() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.url.queryParameter("auth")
                return if (request.url.encodedPath == "/" && auth == "AB23EFGH") {
                    MockResponse.Builder().code(302)
                        .addHeader("Location", "/")
                        .addHeader("Set-Cookie", "dsh_remote_session=v1.testsession; Path=/; HttpOnly; SameSite=Lax")
                        .build()
                } else {
                    MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>ok</html>").build()
                }
            }
        }
        server.start()
        try {
            val env = Env()
            val url = server.url("/").toString()
            env.auth.pairWithDshAuth(url, "AB23EFGH")
            assertTrue(env.auth.state.value is AuthState.LoggedIn)
            val base = server.url("/")
            assertTrue(env.jar.loadForRequest(base).any { it.name == "dsh_remote_session" && it.value.contains("testsession") })
        } finally {
            server.close()
        }
    }

    private class Env {
        val secrets = MemorySecrets()
        val registry = ServerRegistry(secrets)
        val jar = PersistentCookieJar(secrets)
        val http = OkHttpClient.Builder().cookieJar(jar).build()
        val tokens = DeviceTokenStore(secrets)
        val api = MurfPairingApi(http, jar)
        val dshApi = DshRemotePairingApi(http, jar)
        val auth = AuthRepository(
            registry = registry,
            clientFactory = { url -> HermesApiClient(baseUrl = url, client = http, customHeaders = { emptyList() }) },
            probeClientFactory = { url, headers -> HermesApiClient(baseUrl = url, client = http, customHeaders = { headers }) },
            cookieJar = jar,
            deviceSessions = MurfDeviceSessions(api, tokens) { "Test phone" },
            dshPairing = { url, code -> dshApi.claim(url, code) },
        )
    }

    /** Мини-Hermes: WebUI-эндпоинты + relay привязки. */
    private class FakeHermes : Dispatcher() {
        val code = "ABCDEFGHIJKLMNOPQRST"
        private var codeUsed = false
        private var token: String? = null
        private var sessionCounter = 0
        val validSessions = mutableSetOf<String>()
        val claims = CopyOnWriteArrayList<String>()
        @Volatile var revoked = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val body = request.body?.utf8().orEmpty()
            val cookie = request.headers["Cookie"].orEmpty().substringAfter("hermes_session=", "").substringBefore(';')
            return when (path) {
                "/health" -> json("""{"status":"ok"}""")
                "/api/auth/status" -> json("""{"auth_enabled":true,"logged_in":${cookie in validSessions},"password_auth_enabled":true}""")
                "/phone/up/pair/claim" -> synchronized(this) {
                    claims += body
                    if (codeUsed || !body.contains(code)) json("""{"ok":false,"error":"код недействителен"}""", 403)
                    else { codeUsed = true; token = "device-token-xyz"; json("""{"ok":true,"device_id":"d","device_token":"device-token-xyz"}""") }
                }
                "/phone/up/pair/session" -> synchronized(this) {
                    if (revoked || token == null || !body.contains(token!!)) json("""{"ok":false,"error":"отозвано"}""", 401)
                    else {
                        val sid = "s${++sessionCounter}"
                        validSessions += sid
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                            .addHeader("Set-Cookie", "hermes_session=$sid; Path=/; HttpOnly; SameSite=Lax")
                            .body("""{"ok":true}""").build()
                    }
                }
                else -> json("""{}""", if (cookie in validSessions) 200 else 401)
            }
        }

        private fun json(body: String, code: Int = 200) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }
}

internal class MemorySecrets : SecretStore {
    private val values = mutableMapOf<String, String>()
    @Synchronized override fun getString(key: String): String? = values[key]
    @Synchronized override fun putString(key: String, value: String) { values[key] = value }
    @Synchronized override fun remove(key: String) { values.remove(key) }
    @Synchronized override fun clearPrefix(prefix: String) { values.keys.filter { it.startsWith(prefix) }.forEach(values::remove) }
    @Synchronized override fun update(transform: (Map<String, String>) -> Map<String, String>) {
        val updated = transform(values.toMap()); values.clear(); values.putAll(updated)
    }
}
