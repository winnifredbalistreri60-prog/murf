package com.uzairansar.hermex.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Тип провайдера: протокол для DeepSeek Harness (pi-ai) и как получить список моделей. */
enum class ProviderKind(val id: String, val title: String, val api: String, val baseUrl: String, val keyHint: String) {
    DeepSeek("deepseek", "DeepSeek", "openai-completions", "https://api.deepseek.com/v1", "sk-…"),
    OpenAI("openai", "OpenAI", "openai-completions", "https://api.openai.com/v1", "sk-…"),
    Anthropic("anthropic", "Anthropic", "anthropic-messages", "https://api.anthropic.com", "sk-ant-…"),
    Google("google", "Google Gemini", "openai-completions", "https://generativelanguage.googleapis.com/v1beta/openai", "AIza…"),
    XAI("xai", "xAI (Grok)", "openai-completions", "https://api.x.ai/v1", "xai-…"),
    OpenRouter("openrouter", "OpenRouter", "openai-completions", "https://openrouter.ai/api/v1", "sk-or-…"),
    Custom("custom", "OpenAI-совместимый", "openai-completions", "", "");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Custom }
}

data class ProviderConfig(
    val id: String,                 // [a-z0-9_-], уникально
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    val defaultModel: String? = null,
    val modelsFetchedAt: Long = 0,
) {
    fun toJson() = JSONObject().put("id", id).put("kind", kind.id).put("name", name).put("baseUrl", baseUrl)
        .put("models", JSONArray(models)).put("defaultModel", defaultModel).put("modelsFetchedAt", modelsFetchedAt)

    companion object {
        fun fromJson(o: JSONObject) = ProviderConfig(
            o.getString("id"), ProviderKind.of(o.optString("kind")), o.optString("name"), o.optString("baseUrl"),
            o.optJSONArray("models")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            o.optString("defaultModel").takeIf { it.isNotBlank() && it != "null" }, o.optLong("modelsFetchedAt"),
        )
    }
}

/**
 * Провайдеры моделей для локальных агентов MURF. Настройки — в обычных prefs, ключи API — только в
 * EncryptedSharedPreferences (AES-256-GCM, ключ в Android Keystore). Ключ уходит лишь провайдеру (список моделей)
 * и в локальную среду на 127.0.0.1 при запуске агента (переменная окружения процесса), нигде не показывается.
 */
object MurfProviders {
    private const val PREFS = "murf_providers"
    private const val SECURE = "murf_providers_keys"
    private val ID_RE = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var secure: SharedPreferences? = null
    private fun s(c: Context): SharedPreferences = secure ?: synchronized(this) {
        secure ?: EncryptedSharedPreferences.create(
            c.applicationContext, SECURE,
            MasterKey.Builder(c.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { secure = it }
    }

    fun list(c: Context): List<ProviderConfig> = runCatching {
        val a = JSONArray(p(c).getString("list", "[]")); List(a.length()) { ProviderConfig.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveList(c: Context, l: List<ProviderConfig>) =
        p(c).edit().putString("list", JSONArray(l.map { it.toJson() }).toString()).apply()

    fun upsert(c: Context, cfg: ProviderConfig, key: String?) {
        require(ID_RE.matches(cfg.id)) { "bad id" }
        saveList(c, list(c).filterNot { it.id == cfg.id } + cfg)
        if (key != null) s(c).edit().putString(cfg.id, key.trim()).apply()
    }

    fun remove(c: Context, id: String) {
        saveList(c, list(c).filterNot { it.id == id }); s(c).edit().remove(id).apply()
        if (selected(c)?.first == id) p(c).edit().remove("sel").apply()
    }

    fun hasKey(c: Context, id: String) = !s(c).getString(id, null).isNullOrBlank()
    internal fun key(c: Context, id: String): String? = s(c).getString(id, null)

    /**
     * Авторизован ли телефонный Harness для запуска dsh (как method api-key в @deepseek-ai/dsh-authorization):
     * выбран провайдер+модель и есть сохранённый токен (или Custom без ключа).
     */
    fun isAuthorized(c: Context): Boolean {
        val sel = selected(c) ?: return false
        val cfg = list(c).firstOrNull { it.id == sel.first } ?: return false
        return hasKey(c, cfg.id) || cfg.kind == ProviderKind.Custom
    }

    /** Сообщение, если чат/run нельзя стартовать без токена. */
    fun authRequiredMessage(c: Context): String? {
        if (isAuthorized(c)) return null
        val sel = selected(c)
        return if (sel == null) {
            tr(
                "Нужна авторизация: вставьте API-токен провайдера (как «Paste a key» в DeepSeek Harness).",
                "Authorization required: paste a provider API token (like “Paste a key” in DeepSeek Harness).",
            )
        } else {
            tr(
                "Токен для «${sel.first}» не сохранён. Вставьте ключ в блок «Авторизация» ниже.",
                "No token saved for “${sel.first}”. Paste the key in the Authorization block below.",
            )
        }
    }

    fun select(c: Context, provider: String, model: String) = p(c).edit().putString("sel", "$provider\n$model").apply()
    fun selected(c: Context): Pair<String, String>? = p(c).getString("sel", null)?.split("\n")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun newId(c: Context, kind: ProviderKind): String {
        val ids = list(c).map { it.id }.toSet()
        if (kind.id !in ids) return kind.id
        return (2..99).map { "${kind.id}$it" }.first { it !in ids }
    }

    /** Провайдеры для murf_dsh (с ключами — только в теле запроса к 127.0.0.1). */
    fun forLocalRun(c: Context): JSONArray = JSONArray(list(c).filter { hasKey(c, it.id) || it.kind == ProviderKind.Custom }.map { cfg ->
        JSONObject().put("id", cfg.id).put("name", cfg.name).put("api", cfg.kind.api).put("baseURL", cfg.baseUrl)
            .put("models", JSONArray(cfg.models.take(200))).put("key", key(c, cfg.id) ?: "")
    })

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Живой список моделей с API провайдера. Ключ не попадает в текст ошибок. */
    suspend fun fetchModels(kind: ProviderKind, baseUrl: String, key: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl.trimEnd('/')
            require(base.toHttpUrlOrNull() != null) { tr("Неверный адрес API", "Invalid API URL") }
            val rb = Request.Builder().url("$base/" + if (kind == ProviderKind.Anthropic) "v1/models?limit=1000" else "models")
            when (kind) {
                ProviderKind.Anthropic -> rb.header("x-api-key", key).header("anthropic-version", "2023-06-01")
                else -> if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
            }
            try {
                http.newCall(rb.get().build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) error(when (r.code) {
                        401, 403 -> tr("Ключ не принят (HTTP ${r.code})", "Key rejected (HTTP ${r.code})")
                        else -> "HTTP ${r.code}: " + body.replace(key, "***").take(200)
                    })
                    parseModels(body)
                }
            } catch (e: java.net.UnknownHostException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                throw e
            } catch (e: java.io.IOException) {
                // Preserve IO/network cause for ProviderApiErrors; avoid wrapping into generic.
                throw e
            }
        }
    }

    /** {data:[{id}]} (OpenAI/DeepSeek/xAI/OpenRouter/Anthropic/Gemini-OpenAI) или {models:[{name:"models/…"}]} (Gemini). */
    fun parseModels(body: String): List<String> {
        val o = JSONObject(body)
        val a = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
        return List(a.length()) { i ->
            val m = a.optJSONObject(i)
            (m?.optString("id")?.takeIf { it.isNotBlank() } ?: m?.optString("name") ?: a.optString(i)).removePrefix("models/")
        }.filter { it.isNotBlank() }.distinct().sorted()
    }
}
