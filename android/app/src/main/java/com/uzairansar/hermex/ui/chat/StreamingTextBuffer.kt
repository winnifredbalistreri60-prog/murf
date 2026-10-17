package com.uzairansar.hermex.ui.chat

/**
 * MURF: accumulates streamed assistant text without O(n²) string concatenation and
 * decides when the UI may be updated (throttled to [publishIntervalMillis], 50–100 ms)
 * and when the full transcript state should be committed ([commitIntervalMillis]).
 *
 * Only the streaming row observes the published text, so a fast token stream no longer
 * recomposes the whole chat screen on every chunk.
 */
internal class StreamingTextBuffer(
    val publishIntervalMillis: Long = DEFAULT_PUBLISH_INTERVAL_MILLIS,
    val commitIntervalMillis: Long = DEFAULT_COMMIT_INTERVAL_MILLIS,
) {
    private val builder = StringBuilder()
    private var cachedText: String? = ""
    private var lastPublishedLength = 0
    private var lastCommittedLength = 0
    private var lastPublishAtMillis = Long.MIN_VALUE / 2
    private var lastCommitAtMillis = Long.MIN_VALUE / 2

    var ownerId: String? = null
        private set

    val length: Int get() = builder.length
    val hasUnpublishedText: Boolean get() = builder.length != lastPublishedLength
    val hasUncommittedText: Boolean get() = builder.length != lastCommittedLength

    fun reset(ownerId: String?, base: String, nowMillis: Long) {
        this.ownerId = ownerId
        builder.setLength(0)
        builder.append(base)
        cachedText = base
        lastPublishedLength = base.length
        lastCommittedLength = base.length
        lastPublishAtMillis = nowMillis
        lastCommitAtMillis = nowMillis
    }

    fun clear() {
        ownerId = null
        builder.setLength(0)
        cachedText = ""
        lastPublishedLength = 0
        lastCommittedLength = 0
    }

    fun append(chunk: CharSequence) {
        if (chunk.isEmpty()) return
        builder.append(chunk)
        cachedText = null
    }

    fun text(): String = cachedText ?: builder.toString().also { cachedText = it }

    /** Milliseconds to wait before the next publish is allowed (0 = publish now). */
    fun delayUntilPublish(nowMillis: Long): Long =
        (lastPublishAtMillis + publishIntervalMillis - nowMillis).coerceAtLeast(0L)

    fun shouldCommit(nowMillis: Long): Boolean =
        hasUncommittedText && nowMillis - lastCommitAtMillis >= commitIntervalMillis

    fun markPublished(nowMillis: Long) {
        lastPublishedLength = builder.length
        lastPublishAtMillis = nowMillis
    }

    fun markCommitted(nowMillis: Long) {
        lastCommittedLength = builder.length
        lastCommitAtMillis = nowMillis
        markPublished(nowMillis)
    }

    companion object {
        const val DEFAULT_PUBLISH_INTERVAL_MILLIS = 80L
        const val DEFAULT_COMMIT_INTERVAL_MILLIS = 1_000L
    }
}

/** Text of the message that is currently streaming, published separately from [ChatUiState]. */
data class LiveStreamingText(
    val streamId: String,
    val text: String,
)
