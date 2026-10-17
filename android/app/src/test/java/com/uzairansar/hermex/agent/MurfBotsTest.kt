package com.uzairansar.hermex.agent

import com.uzairansar.hermex.ui.chat.TaskStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MurfBotsTest {
    private fun ev(json: String) = BotEvent(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun reducerMergesTextAndNestsSubtaskChildren() {
        val t = BotTranscriptReducer.reduce(listOf(
            ev("""{"seq":1,"type":"user","run":"r1","text":"привет"}"""),
            ev("""{"seq":2,"type":"run","run":"r1","status":"running"}"""),
            ev("""{"seq":3,"type":"thinking","run":"r1","text":"ду"}"""),
            ev("""{"seq":4,"type":"thinking","run":"r1","text":"маю"}"""),
            ev("""{"seq":5,"type":"step","run":"r1","id":"t1","title":"Read","status":"running","started":100.0}"""),
            ev("""{"seq":6,"type":"step","run":"r1","id":"t1","status":"done","detail":"# readme","ended":101.5}"""),
            ev("""{"seq":7,"type":"subtask","run":"r1","id":"d1","title":"отчёт","status":"running","started":102.0}"""),
            ev("""{"seq":8,"type":"step","run":"r1","id":"c1","title":"grep","status":"done","parent":"d1"}"""),
            ev("""{"seq":9,"type":"text","run":"r1","text":"внутри","parent":"d1"}"""),
            ev("""{"seq":10,"type":"subtask","run":"r1","id":"d1","status":"done","ended":110.0}"""),
            ev("""{"seq":11,"type":"text","run":"r1","text":"При"}"""),
            ev("""{"seq":12,"type":"text","run":"r1","text":"вет"}"""),
        ))
        assertTrue(t.running)
        assertEquals(12L, t.lastSeq)
        val items = t.items
        assertTrue(items[0] is BotItem.User)
        assertEquals("думаю", (items[1] as BotItem.Thinking).text)
        val step = items[2] as BotItem.Step
        assertEquals(TaskStatus.Done, step.status)
        assertEquals("# readme", step.detail)
        assertEquals("Read", step.title)
        assertEquals(101_500L, step.endedAtMs)
        val sub = items[3] as BotItem.Subtask
        assertEquals(TaskStatus.Done, sub.status)
        assertEquals(2, sub.children.size)
        assertEquals("внутри", (sub.children[1] as BotItem.Assistant).text)
        assertEquals("Привет", (items[4] as BotItem.Assistant).text)
        assertEquals(5, items.size)
    }

    @Test
    fun reducerTracksApprovalsAndRunEnd() {
        val t = BotTranscriptReducer.reduce(listOf(
            ev("""{"seq":1,"type":"user","run":"r1","text":"x"}"""),
            ev("""{"seq":2,"type":"run","run":"r1","status":"running"}"""),
            ev("""{"seq":3,"type":"approval","run":"r1","id":"a1","title":"rm -rf build","status":"pending","options":[{"id":"allow-once","name":"Allow","kind":"allow_once"},{"id":"no","name":"Reject","kind":"reject_once"}]}"""),
        ))
        assertEquals(1, t.pendingApprovals)
        val a = t.items.last() as BotItem.Approval
        assertEquals(listOf("allow-once", "no"), a.options.map { it.id })
        val t2 = BotTranscriptReducer.reduce(listOf(
            ev("""{"seq":1,"type":"user","run":"r1","text":"x"}"""),
            ev("""{"seq":3,"type":"approval","run":"r1","id":"a1","title":"rm","status":"pending","options":[{"id":"allow-once","name":"Allow"}]}"""),
            ev("""{"seq":4,"type":"approval","run":"r1","id":"a1","status":"resolved","chosen":"allow-once"}"""),
            ev("""{"seq":5,"type":"run","run":"r1","status":"failed","error":"boom"}"""),
            ev("""{"seq":6,"type":"handoff","to_bot":"b2","to_name":"Coder","to_chat":"c2","task":"тест"}"""),
        ))
        assertEquals(0, t2.pendingApprovals)
        assertFalse(t2.running)
        assertEquals("allow-once", (t2.items[1] as BotItem.Approval).chosen)
        assertTrue((t2.items[2] as BotItem.Notice).isError)
        val h = t2.items[3] as BotItem.Handoff
        assertTrue(h.outgoing)
        assertEquals("c2", h.chatId)
    }

    private class FakeBots : Dispatcher() {
        val requests = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val body = request.body?.utf8().orEmpty()
            requests += "${request.method} $path ${request.headers["X-Agent-App"]} $body"
            if (request.headers["X-Agent-App"] != "1") return json("""{"error":"нужен заголовок"}""", 403)
            return when {
                path == "/phone/api/bots/" && request.method == "POST" ->
                    if (body.contains("\"confirm_cli\":true")) json("""{"ok":true,"bot":{"id":"b1","name":"Кодер","backend":"codex"}}""")
                    else json("""{"ok":false,"error":"нужно подтверждение","confirm_required":true,"notice":"Codex CLI будет запускаться…"}""", 409)
                path == "/phone/api/bots/clis" -> json("""{"clis":[{"id":"codex","name":"Codex CLI","installed":true,"version":"codex-cli 0.160.0","logged_in":true,"backend":true}],"profiles":["default"]}""")
                path == "/phone/api/bots/chats/c1/stream" -> MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                    .body("id: 1\nevent: murf\ndata: {\"seq\":1,\"type\":\"user\",\"text\":\"hi\"}\n\n: ping\n\nid: 2\nevent: murf\ndata: {\"seq\":2,\"type\":\"run\",\"status\":\"done\"}\n\n").build()
                else -> json("""{"error":"not found"}""", 404)
            }
        }

        private fun json(body: String, code: Int = 200) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }

    @Test
    fun cliBackendNeedsExplicitConfirmation() = runBlocking {
        val fake = FakeBots()
        MockWebServer().use { server ->
            server.dispatcher = fake
            server.start()
            val api = MurfBotsApi(OkHttpClient(), CookieJar.NO_COOKIES)
            val base = server.url("/")
            try {
                api.save(base, null, BotDraft(name = "Кодер", backend = "codex"), confirm = false)
                fail("expected confirmation")
            } catch (e: BotConfirmRequired) {
                assertTrue(e.notice.startsWith("Codex"))
            }
            val bot = api.save(base, null, BotDraft(name = "Кодер", backend = "codex"), confirm = true)
            assertEquals("b1", bot.id)
            val clis = api.clis(base)
            assertEquals(true, clis.clis.single().loggedIn)
            assertTrue(fake.requests.all { it.contains(" 1 ") })
            assertFalse(fake.requests.first().contains("confirm_cli"))
        }
    }

    @Test
    fun streamParsesServerSentEvents() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = FakeBots()
            server.start()
            val api = MurfBotsApi(OkHttpClient(), CookieJar.NO_COOKIES)
            val events = api.stream(server.url("/"), "c1", 0).toList()
            assertEquals(listOf(1L, 2L), events.map { it.seq })
            assertEquals("done", events[1].str("status"))
        }
    }

    @Test
    fun draftNeverCarriesConfirmUnlessAsked() {
        val j = BotDraft(name = " A ", backend = "grok", access = "cli").toJson(confirm = false)
        assertEquals("\"A\"", j["name"].toString())
        assertFalse(j.containsKey("confirm_cli"))
        assertTrue(BotDraft(name = "A").toJson(confirm = true).containsKey("confirm_cli"))
    }
}
