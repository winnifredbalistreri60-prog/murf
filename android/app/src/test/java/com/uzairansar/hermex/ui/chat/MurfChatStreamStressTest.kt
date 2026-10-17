package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.core.network.SseStreamClient
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import com.uzairansar.hermex.data.repository.ChatRepository
import com.uzairansar.hermex.data.repository.RecordingCacheDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MURF stress test: a long agent answer (big code block, thousands of SSE token events, slow
 * delivery like a high-ping link) must arrive complete while the screen-wide state changes rarely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MurfChatStreamStressTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun longStreamedCodeOutputArrivesCompleteWithFewScreenUpdates() = runTest {
        val tokens = buildList {
            add("Вот длинный код:\n\n```kotlin\n")
            repeat(3_000) { add("val line$it = compute($it) // комментарий\n") }
            add("```\n\nГотово.")
        }
        val expected = tokens.joinToString("")
        val sse = buildString {
            tokens.forEachIndexed { index, token ->
                append("id: s1:${index + 1}\nevent: token\ndata: {\"text\":")
                append(JsonPrimitive(token).toString())
                append("}\n\n")
            }
            append("event: stream_end\ndata: {}\n\n")
        }
        val streamRequests = AtomicInteger()
        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/api/session" -> json(
                        """{"session":{"session_id":"session-1","title":"T","active_stream_id":"s1","is_streaming":true,"messages":[{"role":"user","content":"go"}]}}""",
                    )
                    "/api/chat/stream" -> {
                        streamRequests.incrementAndGet()
                        MockResponse.Builder()
                            .code(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .body(sse)
                            // ~2 s total delivery in small packets, like a slow mobile link.
                            .throttleBody(sse.length / 40L, 50, TimeUnit.MILLISECONDS)
                            .build()
                    }
                    "/api/chat/stream/status" -> json("""{"active":true,"stream_id":"s1"}""")
                    "/api/models" -> json("""{"models":[]}""")
                    "/api/profiles" -> json("""{"profiles":[]}""")
                    "/api/workspaces" -> json("""{"workspaces":[]}""")
                    "/api/reasoning" -> json("""{"supported_efforts":[]}""")
                    "/api/commands" -> json("""{"commands":[]}""")
                    "/api/skills" -> json("""{"skills":[]}""")
                    "/api/session/yolo" -> json("""{"yolo_enabled":false}""")
                    else -> json("""{}""")
                }
            }
            server.start()
            val repository = ChatRepository(
                client = HermesApiClient(server.url("/"), OkHttpClient()),
                cacheDao = RecordingCacheDao(),
                cacheOwnership = ServerCacheOwnership(),
                sse = SseStreamClient(server.url("/"), OkHttpClient()) { emptyList() },
            )
            val store = androidx.lifecycle.ViewModelStore()
            val viewModel = androidx.lifecycle.ViewModelProvider.create(
                store,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(
                        modelClass: kotlin.reflect.KClass<T>,
                        extras: androidx.lifecycle.viewmodel.CreationExtras,
                    ): T = ChatViewModel("session-1", repository) as T
                },
            )[ChatViewModel::class]
            val messageListChanges = AtomicInteger()
            val liveUpdates = AtomicInteger()
            val counter = launch(Dispatchers.Default) {
                var previous: Any? = null
                viewModel.state.collect { state ->
                    if (state.messages !== previous) {
                        previous = state.messages
                        messageListChanges.incrementAndGet()
                    }
                }
            }
            val liveCounter = launch(Dispatchers.Default) {
                viewModel.liveStreaming.collect { if (it != null) liveUpdates.incrementAndGet() }
            }
            val started = System.nanoTime()
            val finalState = withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    viewModel.state.first { state ->
                        !state.isStreaming && state.messages.lastOrNull()?.displayText == expected
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            counter.cancel()
            liveCounter.cancel()
            assertEquals(expected, finalState.messages.last().displayText)
            assertEquals("single SSE connection", 1, streamRequests.get())
            println(
                "MURF VM stress: ${tokens.size} tokens, ${expected.length} chars in $elapsedMs ms; " +
                    "screen-wide message updates=${messageListChanges.get()}, live row updates=${liveUpdates.get()}",
            )
            // Old pacing updated the whole screen every 48 ms word-by-word; now ≈ once per second.
            assertTrue("message list changes=${messageListChanges.get()}", messageListChanges.get() < 40)
            store.clear() // cancels the ViewModel's coroutines before Dispatchers.Main is reset
        } finally {
            server.close()
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()
}
