package com.uzairansar.hermex.agent.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Мини-писатель tar (ustar) для тестов. */
    private class Tar {
        val out = ByteArrayOutputStream()
        fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "", mode: Int = 0b110_100_100, prefix: String = "") {
            val h = ByteArray(512)
            fun put(off: Int, s: String, len: Int) { val b = s.toByteArray(); System.arraycopy(b, 0, h, off, minOf(b.size, len)) }
            fun oct(off: Int, v: Long, len: Int) = put(off, v.toString(8).padStart(len - 1, '0'), len - 1)
            put(0, name, 100); oct(100, mode.toLong(), 8); oct(108, 0, 8); oct(116, 0, 8)
            oct(124, data.size.toLong(), 12); oct(136, 0, 12)
            h[156] = type.code.toByte(); put(157, link, 100)
            put(257, "ustar", 6); put(263, "00", 2); put(345, prefix, 155)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, sum.toString(8).padStart(6, '0') + "\u0000 ", 8)
            out.write(h); out.write(data)
            val pad = (512 - data.size % 512) % 512; out.write(ByteArray(pad))
        }
        fun file(name: String, text: String, mode: Int = 0b110_100_100) = entry(name, '0', text.toByteArray(), mode = mode)
        fun longName(name: String) = entry("././@LongLink", 'L', (name + "\u0000").toByteArray())
        fun pax(vararg kv: Pair<String, String>) {
            val body = StringBuilder()
            for ((k, v) in kv) {
                val rec = " $k=$v\n"; var len = rec.length + 1
                while ((len.toString() + rec).length != len) len++
                body.append(len).append(rec)
            }
            entry("PaxHeaders/x", 'x', body.toString().toByteArray())
        }
        fun gz(): ByteArray { out.write(ByteArray(1024)); val b = ByteArrayOutputStream(); GZIPOutputStream(b).use { it.write(out.toByteArray()) }; return b.toByteArray() }
    }

    private fun extract(t: Tar, exclude: (String) -> Boolean = { false }): Pair<java.io.File, TarExtractor.Stats> {
        val dest = tmp.newFolder("root")
        val st = TarExtractor(dest, exclude).extract(GZIPInputStream(ByteArrayInputStream(t.gz())))
        return dest to st
    }

    @Test
    fun extractsFilesDirsModesSymlinksAndHardlinks() {
        val t = Tar()
        t.entry("./usr/", '5', mode = 0b111_101_101)
        t.entry("./usr/bin/", '5', mode = 0b111_101_101)
        t.file("./usr/bin/bash", "#!bash", mode = 0b111_101_101)
        t.entry("./bin", '2', link = "usr/bin")
        t.entry("./usr/bin/sh", '2', link = "bash")
        t.entry("./usr/bin/rbash", '1', link = "./usr/bin/bash")
        t.entry("./dev/null", '3')
        val (root, st) = extract(t)
        assertEquals("#!bash", root.resolve("usr/bin/bash").readText())
        assertTrue(Files.getPosixFilePermissions(root.resolve("usr/bin/bash").toPath()).contains(PosixFilePermission.OWNER_EXECUTE))
        assertTrue(Files.isSymbolicLink(root.resolve("bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(root.resolve("bin").toPath()).toString())
        assertEquals("bash", Files.readSymbolicLink(root.resolve("usr/bin/sh").toPath()).toString())
        assertFalse(Files.isSymbolicLink(root.resolve("usr/bin/rbash").toPath()))
        assertEquals("#!bash", root.resolve("usr/bin/rbash").readText()) // хардлинк → копия
        assertFalse(root.resolve("dev/null").exists())
        assertEquals(1, st.hardlinks); assertEquals(2, st.symlinks); assertEquals(1, st.skipped)
    }

    @Test
    fun gnuLongNameAndPaxPathAndUstarPrefix() {
        val long = "usr/share/" + "a".repeat(150) + "/file.txt"
        val t = Tar()
        t.longName(long); t.file("ignored", "long")
        t.pax("path" to "etc/pax-name.conf"); t.file("short", "pax")
        t.entry("name.txt", '0', "pref".toByteArray(), prefix = "opt/prefixed")
        val (root, _) = extract(t)
        assertEquals("long", root.resolve(long).readText())
        assertEquals("pax", root.resolve("etc/pax-name.conf").readText())
        assertEquals("pref", root.resolve("opt/prefixed/name.txt").readText())
        assertFalse(root.resolve("ignored").exists()); assertFalse(root.resolve("short").exists())
    }

    @Test
    fun rejectsTraversalAbsoluteEscapeAndSymlinkEscape() {
        val outside = tmp.newFolder("outside")
        val t = Tar()
        t.file("../evil.txt", "x")
        t.file("/etc/abs.txt", "abs") // абсолютный путь → внутрь dest
        t.entry("esc", '2', link = outside.absolutePath)
        t.file("esc/pwned.txt", "pwned")
        val (root, st) = extract(t)
        assertFalse(tmp.root.resolve("evil.txt").exists())
        assertEquals("abs", root.resolve("etc/abs.txt").readText())
        assertFalse(outside.resolve("pwned.txt").exists())
        assertTrue(st.skipped >= 2)
    }

    @Test
    fun excludeFilterSkipsDev() {
        val t = Tar(); t.entry("dev/", '5'); t.file("dev/x", "1"); t.file("etc/y", "2")
        val (root, _) = extract(t) { it == "dev" || it.startsWith("dev/") }
        assertFalse(root.resolve("dev").exists()); assertEquals("2", root.resolve("etc/y").readText())
    }

    @Test
    fun reExtractOverwritesFilesAndLinks() {
        val dest = tmp.newFolder("re")
        val t = Tar(); t.file("a.txt", "v1"); t.entry("l", '2', link = "a.txt")
        TarExtractor(dest).extract(GZIPInputStream(ByteArrayInputStream(t.gz())))
        val t2 = Tar(); t2.file("a.txt", "v2"); t2.entry("l", '2', link = "b.txt")
        TarExtractor(dest).extract(GZIPInputStream(ByteArrayInputStream(t2.gz())))
        assertEquals("v2", dest.resolve("a.txt").readText())
        assertEquals("b.txt", Files.readSymbolicLink(dest.resolve("l").toPath()).toString())
    }

    @Test
    fun parsesBase256Size() {
        val b = ByteArray(512); b[124] = 0x80.toByte(); b[135] = 0x10
        assertEquals(16L, TarExtractor.parseNumber(b, 124, 12))
    }

    @Test
    fun normalizeStripsDotSlashAndSlashes() {
        assertEquals("usr/bin", TarExtractor.normalize("./usr/bin/"))
        assertEquals("etc/x", TarExtractor.normalize("/etc/x"))
        assertEquals("", TarExtractor.normalize("./"))
    }
}
