package com.uzairansar.hermex.agent.embedded

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class RootfsDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sums = """
        6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9 *ubuntu-base-24.04.3-base-amd64.tar.gz
        7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048 *ubuntu-base-24.04.3-base-arm64.tar.gz
        e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86 *ubuntu-base-24.04.5-base-amd64.tar.gz
        a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2 *ubuntu-base-24.04.5-base-arm64.tar.gz
        4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb *ubuntu-base-24.04.5-base-armhf.tar.gz
        9b13c1334eb6d6ead848d117923bcab2b3b54227afa2eabe437cb504d5eda46d *ubuntu-base-24.04.10-base-ppc64el.tar.gz
    """.trimIndent()

    @Test
    fun picksNewestImageForArch() {
        assertEquals(UbuntuBase.Image("ubuntu-base-24.04.5-base-arm64.tar.gz", "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"), UbuntuBase.pick(sums, "arm64"))
        assertEquals("ubuntu-base-24.04.5-base-amd64.tar.gz", UbuntuBase.pick(sums, "amd64")?.name)
        assertEquals("ubuntu-base-24.04.5-base-armhf.tar.gz", UbuntuBase.pick(sums, "armhf")?.name)
        assertNull(UbuntuBase.pick(sums, "riscv64"))
    }

    @Test
    fun archMappingAndVersionCompare() {
        assertEquals("arm64", UbuntuBase.archFor("arm64-v8a")); assertEquals("armhf", UbuntuBase.archFor("armeabi-v7a"))
        assertEquals("amd64", UbuntuBase.archFor("x86_64")); assertNull(UbuntuBase.archFor("x86"))
        assertTrue(UbuntuBase.compareVersions("24.04.10", "24.04.9") > 0)
        assertEquals(0, UbuntuBase.compareVersions("24.04", "24.04.0"))
    }

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    /** Сервер с поддержкой Range/If-Range, опционально «ломается» после N байт первого ответа. */
    private fun server(supportRange: Boolean = true, cutFirstAt: Int = -1, etag: String = "\"v1\""): Pair<MockWebServer, MutableList<RecordedRequest>> {
        val reqs = CopyOnWriteArrayList<RecordedRequest>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reqs += request
                val range = request.headers["Range"]
                val ifRange = request.headers["If-Range"]
                if (supportRange && range != null && (ifRange == null || ifRange == etag)) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    if (from >= payload.size) return MockResponse.Builder().code(416).build()
                    return MockResponse.Builder().code(206).addHeader("ETag", etag)
                        .addHeader("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                        .body(Buffer().write(payload, from, payload.size - from)).build()
                }
                if (cutFirstAt > 0 && reqs.size == 1) {
                    // обрыв: заявляем полный размер, отдаём часть
                    return MockResponse.Builder().code(200).addHeader("ETag", etag)
                        .body(Buffer().write(payload, 0, cutFirstAt))
                        .addHeader("Content-Length", payload.size.toString()).build()
                }
                return MockResponse.Builder().code(200).addHeader("ETag", etag).body(Buffer().write(payload)).build()
            }
        }
        s.start()
        return s to reqs
    }

    private val client = OkHttpClient()

    @Test
    fun fullDownloadVerifiesShaAndRenames() {
        val (s, _) = server()
        try {
            val target = tmp.root.resolve("rootfs.tar.gz")
            var last = 0L
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha, { d, _ -> last = d })
            assertTrue(target.readBytes().contentEquals(payload))
            assertFalse(java.io.File(target.path + ".part").exists())
            assertEquals(payload.size.toLong(), last)
        } finally { s.close() }
    }

    @Test
    fun resumesPartialFileWithRangeAndIfRange() {
        val (s, reqs) = server()
        try {
            val target = tmp.root.resolve("r.tar.gz")
            java.io.File(target.path + ".part").writeBytes(payload.copyOf(100_000))
            java.io.File(target.path + ".part.meta").writeText("\"v1\"")
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(target.readBytes().contentEquals(payload))
            assertEquals("bytes=100000-", reqs.single().headers["Range"])
            assertEquals("\"v1\"", reqs.single().headers["If-Range"])
        } finally { s.close() }
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() {
        val (s, _) = server(supportRange = false)
        try {
            val target = tmp.root.resolve("n.tar.gz")
            java.io.File(target.path + ".part").writeBytes(ByteArray(5000) { 7 })
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(target.readBytes().contentEquals(payload))
        } finally { s.close() }
    }

    @Test
    fun changedFileOnServerRestartsBecauseOfIfRange() {
        val (s, reqs) = server(etag = "\"v2\"")
        try {
            val target = tmp.root.resolve("c.tar.gz")
            java.io.File(target.path + ".part").writeBytes(ByteArray(1000) { 1 }) // кусок старой версии
            java.io.File(target.path + ".part.meta").writeText("\"v1\"")
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(target.readBytes().contentEquals(payload))
            assertEquals("\"v1\"", reqs.first().headers["If-Range"])
        } finally { s.close() }
    }

    @Test
    fun interruptedDownloadKeepsPartAndNextCallResumes() {
        val (s, reqs) = server(cutFirstAt = 120_000)
        try {
            val target = tmp.root.resolve("i.tar.gz")
            try {
                ResumableDownloader(client).download(s.url("/f").toString(), target, sha); fail("must throw")
            } catch (e: java.io.IOException) { /* обрыв */ }
            val part = java.io.File(target.path + ".part")
            assertTrue(part.length() in 1..payload.size.toLong())
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(target.readBytes().contentEquals(payload))
            assertTrue(reqs.last().headers["Range"]!!.startsWith("bytes="))
        } finally { s.close() }
    }

    @Test
    fun alreadyCompletePartGets416AndIsAccepted() {
        val (s, _) = server()
        try {
            val target = tmp.root.resolve("d.tar.gz")
            java.io.File(target.path + ".part").writeBytes(payload)
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(target.readBytes().contentEquals(payload))
        } finally { s.close() }
    }

    @Test
    fun checksumMismatchDeletesPartAndThrows() {
        val (s, reqs) = server()
        try {
            val target = tmp.root.resolve("bad.tar.gz")
            try {
                ResumableDownloader(client).download(s.url("/f").toString(), target, "0".repeat(64)); fail("must throw")
            } catch (e: ChecksumMismatchException) {
                assertEquals(sha, e.actual)
            }
            assertFalse(target.exists()); assertFalse(java.io.File(target.path + ".part").exists())
            assertEquals(2, reqs.size) // одна повторная попытка с нуля
        } finally { s.close() }
    }

    @Test
    fun existingVerifiedTargetIsNotDownloadedAgain() {
        val (s, reqs) = server()
        try {
            val target = tmp.root.resolve("ok.tar.gz"); target.writeBytes(payload)
            ResumableDownloader(client).download(s.url("/f").toString(), target, sha)
            assertTrue(reqs.isEmpty())
        } finally { s.close() }
    }
}
