package com.uzairansar.hermex.agent.embedded

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Распаковка tar (ustar / GNU longname / pax) без внешних программ.
 * Особенности под Android-данные приложения:
 *  - владельцы не сохраняются (proot -0 показывает их как root);
 *  - хардлинки заменяются копией файла (link() в данных приложения запрещён);
 *  - устройства и FIFO пропускаются;
 *  - пути вне [dest] (.., абсолютные, через симлинк наружу) отбрасываются.
 */
class TarExtractor(private val dest: File, private val exclude: (String) -> Boolean = { false }) {
    data class Stats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0, var hardlinks: Int = 0, var skipped: Int = 0)

    private val destCanon = dest.canonicalFile
    private val pendingLinks = mutableListOf<Pair<String, String>>()

    fun extract(input: InputStream, onEntry: (String) -> Unit = {}): Stats {
        val st = Stats()
        destCanon.mkdirs()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var zeroBlocks = 0
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) { if (++zeroBlocks >= 2) break else continue }
            zeroBlocks = 0
            if (!checksumOk(header)) throw IOException("tar: bad header checksum")
            val type = header[156].toInt().toChar()
            val size = parseNumber(header, 124, 12)
            when (type) {
                'L' -> { longName = cString(readData(input, size)); continue }
                'K' -> { longLink = cString(readData(input, size)); continue }
                'x' -> { pax = parsePax(readData(input, size)); continue }
                'g' -> { skipData(input, size); continue }
            }
            val magic = String(header, 257, 5, Charsets.US_ASCII)
            var name = pax["path"] ?: longName ?: run {
                val n = cString(header, 0, 100)
                val prefix = if (magic == "ustar") cString(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            val link = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt()
            longName = null; longLink = null; pax = emptyMap()
            name = normalize(name)
            val target = if (name.isEmpty()) null else safeTarget(name)
            if (target == null || exclude(name)) { st.skipped++; skipData(input, size); continue }
            onEntry(name)
            when (type) {
                '0', '\u0000', '7' -> {
                    deleteIfLink(target)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copyData(input, out, size) }
                    setMode(target, mode or 0b110_000_000)
                    st.files++
                }
                '5' -> {
                    if (Files.isSymbolicLink(target.toPath())) target.delete()
                    target.mkdirs(); setMode(target, mode or 0b111_000_000); st.dirs++
                    skipData(input, size)
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    Files.deleteIfExists(target.toPath())
                    Files.createSymbolicLink(target.toPath(), Paths.get(link))
                    st.symlinks++
                    skipData(input, size)
                }
                '1' -> {
                    pendingLinks += name to normalize(link)
                    st.hardlinks++
                    skipData(input, size)
                }
                else -> { st.skipped++; skipData(input, size) } // '3','4','6': устройства, FIFO
            }
        }
        // хардлинки в конце: источник уже распакован
        for ((name, src) in pendingLinks) {
            val t = safeTarget(name) ?: continue
            val s = safeTarget(src) ?: continue
            if (!s.isFile) continue
            deleteIfLink(t); t.parentFile?.mkdirs()
            s.copyTo(t, overwrite = true)
            runCatching { Files.setPosixFilePermissions(t.toPath(), Files.getPosixFilePermissions(s.toPath())) }
        }
        pendingLinks.clear()
        return st
    }

    private fun safeTarget(name: String): File? {
        if (name.split('/').any { it == ".." }) return null
        val f = File(destCanon, name)
        // родитель (с учётом уже созданных симлинков) обязан остаться внутри dest
        val parent = f.parentFile ?: return null
        val canonParent = runCatching { parent.canonicalFile }.getOrNull() ?: return null
        if (canonParent != destCanon && !canonParent.path.startsWith(destCanon.path + File.separator)) return null
        return File(canonParent, f.name)
    }

    private fun deleteIfLink(f: File) {
        val p = f.toPath()
        if (Files.isSymbolicLink(p) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))) Files.delete(p)
    }

    companion object {
        const val BLOCK = 512

        fun normalize(raw: String): String {
            var n = raw.replace('\\', '/')
            while (n.startsWith("./")) n = n.substring(2)
            n = n.trimStart('/')
            n = n.trimEnd('/')
            return if (n == ".") "" else n
        }

        internal fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
            if (b[off].toInt() and 0x80 != 0) { // base-256 (GNU, большие файлы)
                var v = (b[off].toLong() and 0x7f)
                for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xff)
                return v
            }
            val s = String(b, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
            return if (s.isEmpty()) 0 else s.toLong(8)
        }

        private fun checksumOk(h: ByteArray): Boolean {
            val stored = parseNumber(h, 148, 8)
            var sum = 0L
            for (i in 0 until BLOCK) sum += if (i in 148 until 156) 32 else (h[i].toLong() and 0xff)
            return sum == stored
        }

        private fun cString(b: ByteArray, off: Int = 0, len: Int = b.size): String {
            var end = off
            while (end < off + len && b[end] != 0.toByte()) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        internal fun parsePax(data: ByteArray): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < data.size) {
                val sp = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
                val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || i + len > data.size) break
                val rec = String(data, sp + 1, i + len - sp - 1, Charsets.UTF_8).removeSuffix("\n")
                val eq = rec.indexOf('=')
                if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
                i += len
            }
            return out
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) { if (n == 0) return false else throw IOException("tar: truncated") }
                n += r
            }
            return true
        }

        private fun padded(size: Long) = (size + BLOCK - 1) / BLOCK * BLOCK

        private fun readData(input: InputStream, size: Long): ByteArray {
            require(size in 0..(16L shl 20)) { "tar: header record too big" }
            val buf = ByteArray(padded(size).toInt())
            if (!readFully(input, buf)) throw IOException("tar: truncated")
            return buf.copyOf(size.toInt())
        }

        private fun skipData(input: InputStream, size: Long) {
            var left = padded(size)
            val buf = ByteArray(8192)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("tar: truncated")
                left -= r
            }
        }

        private fun copyData(input: InputStream, out: java.io.OutputStream, size: Long) {
            var left = size
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("tar: truncated")
                out.write(buf, 0, r); left -= r
            }
            val pad = padded(size) - size
            if (pad > 0) skipExact(input, pad)
        }

        private fun skipExact(input: InputStream, n: Long) {
            var left = n
            val buf = ByteArray(BLOCK)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("tar: truncated")
                left -= r
            }
        }

        fun setMode(f: File, mode: Int) {
            val p = mutableSetOf<PosixFilePermission>()
            val bits = listOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
            )
            for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) p += bits[i]
            runCatching { Files.setPosixFilePermissions(f.toPath(), p) }.onFailure {
                f.setReadable(true, true); f.setWritable(true, true)
                if (mode and 0b001_001_001 != 0) f.setExecutable(true, false)
            }
        }
    }
}
