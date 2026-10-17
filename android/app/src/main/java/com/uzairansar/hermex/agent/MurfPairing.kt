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

/** Содержимое QR привязки: murf://pair?v=1&u=<адрес сервера>&c=<одноразовый код>. */
data class PairPayload(val server: HttpUrl, val code: String)

object PairPayloadParser {
    private val CODE = Regex("^[A-Z2-7]{16,40}$")

    /** Разбирает QR или ручной ввод «адрес код». Возвращает null для чужих/опасных данных. */
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
            return build(params["u"].orEmpty(), params["c"].orEmpty())
        }
        val parts = text.split(Regex("\\s+"))
        if (parts.size == 2) return build(parts[0], parts[1])
        return null
    }

    private fun build(serverText: String, codeText: String): PairPayload? {
        val code = codeText.trim().uppercase()
        if (!CODE.matches(code)) return null
        val server = serverText.trim().toHttpUrlOrNull() ?: return null
        if (server.username.isNotEmpty() || server.password.isNotEmpty()) return null
        // только HTTPS, кроме адресов локальной сети (как и при обычном входе)
        if (!server.isHttps && !isPrivateNetworkHost(server.host)) return null
        val base = server.newBuilder().encodedPath("/").query(null).fragment(null).build()
        return PairPayload(base, code)
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
