package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.UploadResponse
import com.uzairansar.hermex.core.network.ApiError
import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID

/** MURF: a message written while offline; it is sent automatically when the connection returns. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OutboxItem(
    // EncodeDefault: HermesJson skips defaults, and a regenerated id/time on every load would break edit/cancel.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = UUID.randomUUID().toString(),
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val createdAtMillis: Long = System.currentTimeMillis(),
    val attachments: List<UploadResponse> = emptyList(),
)

@Serializable
internal data class OutboxFile(val version: Int = 1, val items: List<OutboxItem> = emptyList())

/**
 * Persists the offline outbox of one chat (server + session) as a small JSON file.
 * Writes are atomic (temp file + rename) so a crash cannot corrupt the queue.
 */
internal class OfflineOutboxStore(private val directory: File, key: String) {
    private val file = File(directory, sha256(key) + ".json")

    @Synchronized
    fun load(): List<OutboxItem> = runCatching {
        if (!file.isFile) return emptyList()
        HermesJson.decodeFromString<OutboxFile>(file.readText(Charsets.UTF_8)).items
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(items: List<OutboxItem>) {
        runCatching {
            if (items.isEmpty()) {
                file.delete()
                return
            }
            directory.mkdirs()
            val temp = File(directory, file.name + ".tmp")
            temp.writeText(HermesJson.encodeToString(OutboxFile.serializer(), OutboxFile(items = items)), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object OfflineOutboxPolicy {
    /**
     * True only when the request certainly never reached the server (DNS / connect failure),
     * so re-sending it later cannot create a duplicate turn.
     */
    fun isConnectFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            if (current is UnknownHostException || current is ConnectException || current is NoRouteToHostException) return true
            current = current.cause
            depth += 1
        }
        return false
    }

    fun shouldQueue(error: Throwable): Boolean =
        (error is ApiError.Network || error is java.io.IOException) && isConnectFailure(error)
}

internal object ReadOnlySessionPolicy {
    fun isReadOnlyRejection(error: Throwable): Boolean {
        val http = error as? ApiError.Http ?: return false
        if (http.statusCode != 403) return false
        val body = http.body.orEmpty().lowercase()
        return "read-only" in body || "read only" in body || "readonly" in body || "cannot be continued" in body
    }

    fun message(source: String?): String {
        val isTelegram = source.isNullOrBlank() || source.contains("telegram", ignoreCase = true)
        return if (isTelegram) {
            com.uzairansar.hermex.agent.tr(
                "Это сессия из Telegram, только чтение. Начните новый чат.",
                "This is a Telegram session (read-only). Start a new chat.",
            )
        } else {
            com.uzairansar.hermex.agent.tr(
                "Это импортированная сессия ($source), только чтение. Начните новый чат.",
                "This is an imported session ($source), read-only. Start a new chat.",
            )
        }
    }
}
