package com.uzairansar.hermex.agent

import com.uzairansar.hermex.core.network.isPrivateNetworkHost
import com.uzairansar.hermex.data.secure.SecretStore
import com.uzairansar.hermex.data.secure.ServerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Содержимое QR/ссылки привязки.
 * - [PairProtocol.HermesMurf]: murf://pair?v=1&u=&c= → relay `/phone/up/pair/…` → cookie `hermes_session`
 * - [PairProtocol.DshRemote]: `https://host/?auth=CODE` (DeepSeek Harness remote) → cookie `dsh_remote_session`
 *
 * После DSH-сопряжения сессия годится для веб-UI Harness (cookie), а не для HermesApiClient chat API.
 */
enum class PairProtocol { HermesMurf, DshRemote }

data class PairPayload(
    val server: HttpUrl,
    val code: String,
    val protocol: PairProtocol = PairProtocol.HermesMurf,
)

object PairPayloadParser {
    /** Коды MURF/Hermes: base32 без 0/1/8/9, длина 16–40. */
    private val HERMES_CODE = Regex("^[A-Z2-7]{16,40}$")
    /** Коды DSH remote: алфавит без 0/1/I/O, ровно 8 символов. */
    private val DSH_CODE = Regex("^[2-9A-HJ-NP-Z]{8}$")

    /** Разбирает QR или ручной ввод. Возвращает null для чужих/опасных данных. */
    fun parse(raw: String): PairPayload? {
        val text = raw.trim()
        if (text.startsWith("murf://pair", ignoreCase = true)) {
            val query = text.substringAfter('?', "")
            val params = query.split('&').mapNotNull { part ->
                val k = part.substringBefore('=', "")
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull().orEmpty()
            }.toMap()
            val version = params["v"]?.toIntOrNull() ?: 1
            if (version != 1) return null
            return buildHermes(params["u"].orEmpty(), params["c"].orEmpty())
        }
        // DeepSeek Harness remote: https://host/?auth=CODE (или http://LAN:port/?auth=)
        text.toHttpUrlOrNull()?.let { url ->
            val auth = url.queryParameter("auth")?.trim().orEmpty()
            if (auth.isNotEmpty()) return buildDsh(url, auth)
        }
        val parts = text.split(Regex("\\s+"))
        if (parts.size == 2) {
            val code = parts[1].trim().uppercase()
            if (DSH_CODE.matches(code)) return buildDshUrl(parts[0], code)
            if (HERMES_CODE.matches(code)) return buildHermes(parts[0], code)
        }
        return null
    }

    private fun buildHermes(serverText: String, codeText: String): PairPayload? {
        val code = codeText.trim().uppercase()
        if (!HERMES_CODE.matches(code)) return null
        val base = normalizeServer(serverText) ?: return null
        return PairPayload(base, code, PairProtocol.HermesMurf)
    }

    private fun buildDshUrl(serverText: String, code: String): PairPayload? {
        val base = normalizeServer(serverText) ?: return null
        return PairPayload(base, code, PairProtocol.DshRemote)
    }

    private fun buildDsh(url: HttpUrl, codeText: String): PairPayload? {
        val code = codeText.trim().uppercase()
        if (!DSH_CODE.matches(code)) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (!url.isHttps && !isPrivateNetworkHost(url.host)) return null
        val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        return PairPayload(base, code, PairProtocol.DshRemote)
    }

    private fun normalizeServer(serverText: String): HttpUrl? {
        val server = serverText.trim().toHttpUrlOrNull() ?: return null
        if (server.username.isNotEmpty() || server.password.isNotEmpty()) return null
        // только HTTPS, кроме адресов локальной сети (как и при обычном входе)
        if (!server.isHttps && !isPrivateNetworkHost(server.host)) return null
        return server.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }
}

/** Токен устройства хранится в зашифрованном хранилище (AES-GCM, ключ в Android Keystore). */
class DeviceTokenStore(private val secrets: SecretStore) {
    fun token(server: HttpUrl): String? = secrets.getString(key(server))
    fun save(server: HttpUrl, token: String) = secrets.putString(key(server), token)
    fun remove(server: HttpUrl) = secrets.remove(key(server))
    fun hasToken(server: HttpUrl) = !token(server).isNullOrBlank()

    fun deviceId(): String = secrets.getString(DEVICE_ID_KEY) ?: newDeviceId().also { secrets.putString(DEVICE_ID_KEY, it) }

    private fun key(server: HttpUrl) = "murf.device_token::" + ServerRegistry.normalizedId(server)

    private fun newDeviceId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return "a-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object { const val DEVICE_ID_KEY = "murf.device_id" }
}

@Serializable
data class PairedDevice(
    val id: String = "",
    val name: String = "",
    val label: String = "",
    val created: Long = 0,
    @SerialName("last_used") val lastUsed: Long = 0,
    @SerialName("last_ip") val lastIp: String = "",
)

@Serializable
data class PairedDevicesResponse(
    val ok: Boolean = false,
    val devices: List<PairedDevice> = emptyList(),
    @SerialName("webui_secret_configured") val webuiSecretConfigured: Boolean = false,
    val error: String? = null,
)

@Serializable
data class PairCodeResponse(
    val ok: Boolean = false,
    val code: String = "",
    val payload: String = "",
    val server: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val error: String? = null,
)

@Serializable
private data class ClaimResponse(
    val ok: Boolean = false,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_token") val deviceToken: String = "",
    val error: String? = null,
)

@Serializable
private data class ErrorResponse(val error: String? = null)

sealed interface DeviceSessionResult {
    data object Ok : DeviceSessionResult
    data object Revoked : DeviceSessionResult
    data class Failed(val message: String) : DeviceSessionResult
}

class PairingException(message: String) : IOException(message)

/**
 * Сопряжение с DeepSeek Harness remote access (`?auth=` → cookie `dsh_remote_session`).
 * Не вызывает Hermes `/phone/up/pair/…`. Родные chat API Hermex остаются Hermes-specific;
 * после успеха сервер в [ServerRegistry] и cookie в общем jar — для открытия веб-UI Harness
 * (Custom Tab / WebView). TODO: отдельный экран/Custom Tab для DSH, если ещё нет.
 */
class DshRemotePairingApi(
    baseClient: OkHttpClient,
    private val cookieJar: CookieJar,
) {
    private val client = baseClient.newBuilder().cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** GET `/?auth=CODE`, сохранить Set-Cookie `dsh_remote_session` в общий jar. */
    suspend fun claim(server: HttpUrl, code: String) = withContext(Dispatchers.IO) {
        val target = server.newBuilder().encodedPath("/").query(null).addQueryParameter("auth", code).build()
        val response = client.newCall(Request.Builder().url(target).get().build()).execute()
        response.use { r ->
            val cookies = cookieJar.loadForRequest(server)
            val hasSession = cookies.any { it.name == "dsh_remote_session" && it.value.isNotBlank() }
            if (!hasSession) {
                throw PairingException(
                    tr(
                        "Harness не выдал сессию (проверьте код и что доступ извне включён)",
                        "Harness did not issue a session (check the code and that remote access is enabled)",
                    ),
                )
            }
            // 302→200 ожидаемы; 401/403 — отказ
            if (r.code == 401 || r.code == 403 || r.code == 429) {
                throw PairingException(tr("код Harness отклонён или слишком много попыток", "Harness code rejected or rate-limited") + " (HTTP ${r.code})")
            }
        }
    }
}


/** HTTP-клиент эндпоинтов привязки relay MURF. */
class MurfPairingApi(
    baseClient: OkHttpClient,
    private val cookieJar: CookieJar,
) {
    private val client = baseClient.newBuilder().cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    private fun url(server: HttpUrl, path: String) = server.newBuilder().encodedPath(path).query(null).build()

    private fun post(server: HttpUrl, path: String, body: String, appHeader: Boolean = false): Pair<Int, String> {
        val req = Request.Builder().url(url(server, path)).post(body.toRequestBody(jsonType))
        if (appHeader) req.header("X-Agent-App", "1")
        return client.newCall(req.build()).execute().use { it.code to it.body.string() }
    }

    private fun errorOf(code: Int, body: String): String =
        runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: when (code) {
                404 -> tr("на сервере нет relay MURF с привязкой по QR (обновите сервер)", "the server has no MURF pairing relay (update the server)")
                429 -> tr("слишком много попыток, подождите минуту", "too many attempts, wait a minute")
                else -> "HTTP $code"
            }

    /** Обменять одноразовый код на токен устройства. */
    suspend fun claim(server: HttpUrl, code: String, deviceId: String, name: String): String = withContext(Dispatchers.IO) {
        val body = """{"code":${q(code)},"device_id":${q(deviceId)},"name":${q(name)}}"""
        val (status, text) = post(server, "/phone/up/pair/claim", body)
        if (status != 200) throw PairingException(errorOf(status, text))
        val res = json.decodeFromString<ClaimResponse>(text)
        if (!res.ok || res.deviceToken.isBlank()) throw PairingException(res.error ?: tr("сервер не выдал токен", "server returned no token"))
        res.deviceToken
    }

    /** Получить новую cookie WebUI по токену устройства (cookie сохраняется в общий cookie jar). */
    suspend fun session(server: HttpUrl, token: String): DeviceSessionResult = withContext(Dispatchers.IO) {
        try {
            val (status, text) = post(server, "/phone/up/pair/session", """{"device_token":${q(token)}}""")
            when (status) {
                200 -> DeviceSessionResult.Ok
                401, 403 -> DeviceSessionResult.Revoked
                else -> DeviceSessionResult.Failed(errorOf(status, text))
            }
        } catch (e: IOException) {
            DeviceSessionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun devices(server: HttpUrl): PairedDevicesResponse = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url(server, "/phone/api/pair/devices")).get().build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw PairingException(errorOf(r.code, text))
            json.decodeFromString<PairedDevicesResponse>(text)
        }
    }

    suspend fun revoke(server: HttpUrl, deviceId: String) = withContext(Dispatchers.IO) {
        val (status, text) = post(server, "/phone/api/pair/revoke", """{"device_id":${q(deviceId)}}""", appHeader = true)
        if (status != 200) throw PairingException(errorOf(status, text))
    }

    suspend fun newCode(server: HttpUrl, label: String = ""): PairCodeResponse = withContext(Dispatchers.IO) {
        val (status, text) = post(server, "/phone/api/pair/new", """{"label":${q(label)},"ttl":180}""", appHeader = true)
        if (status != 200) throw PairingException(errorOf(status, text))
        json.decodeFromString<PairCodeResponse>(text)
    }

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

class MurfDeviceSessions(
    private val api: MurfPairingApi,
    private val store: DeviceTokenStore,
    private val deviceName: () -> String,
) : com.uzairansar.hermex.data.repository.DeviceSessions {
    override fun hasToken(server: HttpUrl) = store.hasToken(server)

    override suspend fun claim(server: HttpUrl, code: String) {
        val token = api.claim(server, code, store.deviceId(), deviceName())
        store.save(server, token)
    }

    override suspend fun refresh(server: HttpUrl): DeviceSessionResult =
        store.token(server)?.let { api.session(server, it) } ?: DeviceSessionResult.Revoked

    override suspend fun selfRevoke(server: HttpUrl) {
        if (store.hasToken(server)) {
            runCatching { api.revoke(server, store.deviceId()) }
        }
        store.remove(server)
    }

    override fun forget(server: HttpUrl) = store.remove(server)
}
