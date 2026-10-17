package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurfStreamingBufferTest {
    @Test
    fun publishIsThrottledAndCommitIsRare() {
        val buffer = StreamingTextBuffer(publishIntervalMillis = 80, commitIntervalMillis = 1_000)
        buffer.reset("s1", base = "", nowMillis = 0)
        var now = 0L
        var publishes = 0
        var commits = 0
        // 10 s of streaming, one token every 5 ms (200 tokens/s).
        while (now < 10_000) {
            buffer.append("tok ")
            if (buffer.delayUntilPublish(now) == 0L) {
                if (buffer.shouldCommit(now)) {
                    buffer.markCommitted(now); commits += 1
                } else if (buffer.hasUnpublishedText) {
                    buffer.text(); buffer.markPublished(now); publishes += 1
                }
            }
            now += 5
        }
        assertTrue("publishes=$publishes", publishes in 100..130) // ≈ 10 000 / 80
        assertTrue("commits=$commits", commits in 8..11) // ≈ once per second
        assertEquals(2_000 * 4, buffer.length)
    }

    @Test
    fun resetKeepsBaseTextAndTracksOwner() {
        val buffer = StreamingTextBuffer()
        buffer.reset("stream-a", base = "Hello", nowMillis = 0)
        buffer.append(", world")
        assertEquals("stream-a", buffer.ownerId)
        assertEquals("Hello, world", buffer.text())
        assertTrue(buffer.hasUncommittedText)
        buffer.markCommitted(10)
        assertFalse(buffer.hasUncommittedText)
        buffer.clear()
        assertEquals(null, buffer.ownerId)
        assertEquals("", buffer.text())
    }

    @Test
    fun stressMegabytesOfOutputStayLinear() {
        // Large fake agent output: ~4 MB of code in 200 000 small chunks.
        val chunk = "    val x = listOf(1, 2, 3) // строка кода\n"
        val buffer = StreamingTextBuffer()
        buffer.reset("s", "", 0)
        val started = System.nanoTime()
        var now = 0L
        repeat(200_000) {
            buffer.append(chunk)
            now += 1
            if (buffer.delayUntilPublish(now) == 0L) {
                buffer.text()
                buffer.markPublished(now)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(chunk.length * 200_000, buffer.length)
        // The old String += path copied the whole transcript per token (O(n²) ≈ 10^12 chars here).
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
        println("MURF stress: ${buffer.length} chars, 200000 chunks, ${elapsedMs} ms")
    }
}

class MurfCodeBlockTest {
    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }

    @Test
    fun longBlocksCollapseToFortyLines() {
        val code = lines(500)
        assertTrue(CodeBlockPresentation.isCollapsible(code))
        assertFalse(CodeBlockPresentation.isCollapsible(lines(40)))
        val head = CodeBlockPresentation.preview(code)
        assertEquals(40, CodeBlockPresentation.lineCount(head))
        assertTrue(head.startsWith("line 1\n"))
        val tail = CodeBlockPresentation.preview(code, fromEnd = true)
        assertEquals(40, CodeBlockPresentation.lineCount(tail))
        assertTrue(tail.endsWith("line 500"))
        assertTrue(tail.startsWith("line 461"))
    }

    @Test
    fun chunksCoverEveryLineExactlyOnce() {
        val code = lines(1_234)
        val chunks = CodeBlockPresentation.chunks(code, linesPerChunk = 200)
        assertEquals(7, chunks.size)
        assertEquals(code, chunks.joinToString("\n"))
    }

    @Test
    fun unclosedFenceWhileStreamingIsAnOpenCodeBlockNotMarkdown() {
        val streaming = "Вот код:\n\n```kotlin\nfun a() = 1\nfun b() = 2"
        val segments = streaming.parseMarkdownSegments()
        val code = segments.last() as MarkdownSegment.CodeBlock
        assertTrue(code.isOpen)
        assertEquals("kotlin", code.language)
        assertEquals("fun a() = 1\nfun b() = 2", code.content)
        assertTrue(segments.first() is MarkdownSegment.Markdown)

        val finished = "$streaming\n```\nДальше текст"
        val closed = finished.parseMarkdownSegments()
        assertFalse(closed.filterIsInstance<MarkdownSegment.CodeBlock>().single().isOpen)
    }

    @Test
    fun highlighterProducesColouredSpansAndSkipsUnknownLanguages() {
        val result = CodeHighlighter.highlight("fun main() { val s = \"hi\" }", "kotlin", isDark = true)
        requireNotNull(result)
        assertEquals("fun main() { val s = \"hi\" }", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
        assertEquals(null, CodeHighlighter.highlight("echo hi", "bash", isDark = true))
        assertEquals("kotlin", CodeHighlighter.grammarName("KT"))
    }

    @Test
    fun splitterKeepsChunksSmallForIncrementalParsing() {
        val text = (1..400).joinToString("\n\n") { "Абзац номер $it с текстом." }
        val segments = StreamingMarkdownBlockSplitter.split(text)
        assertTrue(segments.stableChunks.size >= 4)
        assertTrue(segments.activeMarkdown.length <= StreamingMarkdownBlockSplitter.STABLE_CHUNK_TARGET_CHARACTER_COUNT + 200)
        assertEquals(text, segments.stableChunks.joinToString("") { it.text } + segments.activeMarkdown)
    }
}
