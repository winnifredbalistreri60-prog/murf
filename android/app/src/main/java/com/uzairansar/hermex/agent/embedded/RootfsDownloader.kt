package com.uzairansar.hermex.agent.embedded

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Выбор образа ubuntu-base 24.04 по ABI и SHA256SUMS (cdimage.ubuntu.com). */
object UbuntuBase {
    const val DEFAULT_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

    fun archFor(abi: String): String? = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        else -> null
    }

    data class Image(val name: String, val sha256: String)

    /** Самая новая точечная версия для [arch] из файла SHA256SUMS. */
    fun pick(sums: String, arch: String): Image? {
        val re = Regex("""^([0-9a-fA-F]{64})\s+\*?(ubuntu-base-([0-9.]+)-base-${Regex.escape(arch)}\.tar\.gz)\s*$""")
        return sums.lineSequence().mapNotNull { re.find(it.trim()) }
            .maxWithOrNull { a, b -> compareVersions(a.groupValues[3], b.groupValues[3]) }
            ?.let { Image(it.groupValues[2], it.groupValues[1].lowercase()) }
    }

    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

class ChecksumMismatchException(val expected: String, val actual: String) :
    IOException("sha256 mismatch: expected $expected, got $actual")

/**
 * Загрузка с докачкой: файл `<target>.part`, HTTP Range + If-Range (ETag/Last-Modified в `<target>.part.meta`),
 * проверка sha256 по завершении, атомарное переименование в [target].
 */
class ResumableDownloader(private val client: OkHttpClient) {
    fun interface Progress { fun update(done: Long, total: Long) }

    fun download(url: String, target: File, sha256: String?, progress: Progress = Progress { _, _ -> }, cancelled: () -> Boolean = { false }): File {
        if (target.isFile && (sha256 == null || sha256(target) == sha256.lowercase())) {
            progress.update(target.length(), target.length()); return target
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val meta = File(part.path + ".meta")
        for (attempt in 0..1) {
            fetch(url, part, meta, progress, cancelled)
            val actual = sha256(part)
            if (sha256 == null || actual == sha256.lowercase()) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("rename ${part.name} failed")
                meta.delete()
                return target
            }
            part.delete(); meta.delete()
            if (attempt == 1) throw ChecksumMismatchException(sha256.lowercase(), actual)
        }
        error("unreachable")
    }

    private fun fetch(url: String, part: File, meta: File, progress: Progress, cancelled: () -> Boolean) {
        val have = if (part.isFile) part.length() else 0L
        val validator = if (have > 0 && meta.isFile) meta.readText().trim().ifEmpty { null } else null
        val rb = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (have > 0) {
            rb.header("Range", "bytes=$have-")
            validator?.let { rb.header("If-Range", it) }
        }
        client.newCall(rb.build()).execute().use { r ->
            val append = when {
                r.code == 206 && have > 0 -> true
                r.code == 416 && have > 0 -> { progress.update(have, have); return } // уже всё скачано
                r.isSuccessful -> false
                else -> throw IOException("HTTP ${r.code} for $url")
            }
            if (!append) {
                val v = r.header("ETag") ?: r.header("Last-Modified")
                if (v != null) meta.writeText(v) else meta.delete()
            }
            val body = r.body
            val start = if (append) have else 0L
            val total = if (body.contentLength() >= 0) start + body.contentLength() else -1L
            var done = start
            var lastReport = 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("download cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - lastReport >= 512 * 1024) { lastReport = done; progress.update(done, total) }
                    }
                }
            }
            progress.update(done, total)
            if (total >= 0 && done != total) throw IOException("incomplete download: $done of $total")
        }
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
