package com.uzairansar.hermex.agent.embedded

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import com.uzairansar.hermex.agent.TermuxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Встроенная среда MURF: proot из APK (nativeLibraryDir) + Ubuntu в filesDir/murf/rootfs.
 * Termux не нужен. Каталог filesDir/murf/host = ~/.murf-local Termux-режима (скрипты, логи, progress.json),
 * внутри Ubuntu он виден как /murf-host.
 */
object EmbeddedRuntime {
    val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    const val ROOTFS_MARKER = ".murf-rootfs-ok"
    private const val MAX_OUTPUT = 256 * 1024

    fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    fun baseDir(c: Context) = File(c.filesDir, "murf")
    fun hostDir(c: Context) = File(baseDir(c), "host")
    fun rootfsDir(c: Context) = File(baseDir(c), "rootfs")
    fun logsDir(c: Context) = File(hostDir(c), "logs")
    fun cacheDir(c: Context) = File(baseDir(c), "dl")

    fun layout(c: Context): ProotLayout {
        val base = baseDir(c)
        return ProotLayout(
            nativeLibDir = c.applicationInfo.nativeLibraryDir, // не кешировать: меняется после обновления APK
            rootfs = File(base, "rootfs").path,
            hostDir = File(base, "host").path,
            tmpDir = File(base, "tmp").path,
            fakeProcDir = File(base, "fakeproc").path,
        )
    }

    /** Есть ли proot под ABI устройства (на x86 и экзотике — только Termux). */
    fun isSupported(c: Context): Boolean = abi() != null && File(layout(c).proot).canExecute()

    fun rootfsReady(c: Context): Boolean =
        File(rootfsDir(c), ROOTFS_MARKER).isFile && File(rootfsDir(c), "usr/bin/bash").exists()

    fun installed(c: Context): Boolean {
        val r = rootfsDir(c)
        return rootfsReady(c) && File(r, "root/.hermes/hermes-agent/.hermes/bin/hermes").exists() &&
            File(r, "root/.murf/steps/config.done").isFile
    }

    fun noSeccomp(c: Context) = File(hostDir(c), "noseccomp").isFile

    fun freeMb(c: Context): Long = runCatching { StatFs(c.filesDir.path).availableBytes / (1024 * 1024) }.getOrDefault(-1)

    /** Скрипты murf-local из assets → filesDir/murf/host (аналог pushScripts для Termux). */
    fun syncScripts(c: Context): String {
        val host = hostDir(c)
        host.mkdirs(); File(host, "logs").mkdirs()
        c.assets.open("murf-local.tgz").use { a -> TarExtractor(host).extract(GZIPInputStream(a.buffered())) }
        File(host, "murf-local").setExecutable(true, false)
        return "murf-local " + runCatching { File(host, "VERSION").readText().trim() }.getOrDefault("?")
    }

    /** Подготовка перед каждым запуском proot: каталоги, фейки /proc, resolv.conf. */
    fun prepare(c: Context): List<String> {
        val l = layout(c)
        File(l.tmpDir).mkdirs(); File(l.hostDir, "logs").mkdirs()
        File(l.rootfs, "tmp").apply { mkdirs(); runCatching { TarExtractor.setMode(this, 0b111_111_111) } }
        File(l.rootfs, "dev/shm").mkdirs()
        writeResolvConf(c)
        val fp = File(l.fakeProcDir).apply { mkdirs() }
        val kernel = System.getProperty("os.version").orEmpty()
        return ProotCommand.FAKE_PROC.filter { f ->
            val readable = runCatching { File("/proc/$f").readText().isNotEmpty() }.getOrDefault(false)
            if (!readable) File(fp, f).writeText(ProotCommand.fakeProcContent(f, kernel))
            !readable
        }
    }

    fun writeResolvConf(c: Context) {
        val etc = File(rootfsDir(c), "etc")
        if (!etc.isDirectory) return
        val dns = runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        }.getOrDefault(emptyList())
        val f = File(etc, "resolv.conf")
        // в ubuntu-base resolv.conf — симлинк на systemd-resolved; заменяем обычным файлом
        runCatching { java.nio.file.Files.deleteIfExists(f.toPath()) }
        f.writeText(ProotCommand.resolvConf(dns))
        val hosts = File(etc, "hosts")
        if (!hosts.isFile || !hosts.readText().contains("localhost")) hosts.appendText("127.0.0.1 localhost\n::1 localhost\n")
    }

    fun processBuilder(c: Context, guest: List<String>): ProcessBuilder {
        val l = layout(c)
        val fake = prepare(c)
        val pb = ProcessBuilder(ProotCommand.args(l, guest, fake))
        pb.environment().clear()
        pb.environment().putAll(ProotCommand.env(l, noSeccomp(c), File(l.loader32).exists()))
        pb.directory(File(l.hostDir))
        return pb
    }

    fun androidInfo(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    fun murfLocalCommand(args: List<String>): List<String> = ProotCommand.murfLocal(
        args,
        mapOf("MURF_ANDROID_INFO" to androidInfo()),
    )

    /** Выполнить команду в Ubuntu и дождаться результата (stdout/stderr, код выхода). */
    suspend fun exec(c: Context, guest: List<String>, timeoutMs: Long): TermuxResult = withContext(Dispatchers.IO) {
        if (!isSupported(c)) return@withContext TermuxResult("", "", null, "embedded runtime is not available on ${Build.SUPPORTED_ABIS.joinToString()}")
        if (!rootfsReady(c)) return@withContext TermuxResult("", "", null, "Ubuntu is not installed yet")
        val p = try { processBuilder(c, guest).start() } catch (e: Exception) {
            return@withContext TermuxResult("", "", null, "proot: ${e.message}")
        }
        p.outputStream.close()
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = Thread { drain(p.inputStream, out) }.apply { start() }
        val t2 = Thread { drain(p.errorStream, err) }.apply { start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        t1.join(2000); t2.join(2000)
        TermuxResult(
            stdout = synchronized(out) { out.toString() },
            stderr = synchronized(err) { err.toString() },
            exitCode = if (done) p.exitValue() else null,
            error = if (done) null else "timeout ${timeoutMs / 1000} s",
        )
    }

    suspend fun murfLocal(c: Context, args: List<String>, timeoutMs: Long): TermuxResult =
        exec(c, murfLocalCommand(args), timeoutMs)

    private fun drain(s: java.io.InputStream, sb: StringBuilder) {
        s.bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf); if (n < 0) break
                synchronized(sb) { sb.append(buf, 0, n); if (sb.length > MAX_OUTPUT) sb.delete(0, sb.length - MAX_OUTPUT) }
            }
        }
    }

    // ---------- прогресс и статус без Ubuntu ----------

    fun writeProgress(c: Context, state: String, pct: Int, step: String, msg: String, log: Boolean = true) {
        val host = hostDir(c).apply { mkdirs() }
        val j = JSONObject().put("phase", "embedded").put("state", state).put("pct", pct).put("step", step)
            .put("msg", msg).put("ts", System.currentTimeMillis() / 1000)
        val tmp = File(host, "progress.json.tmp")
        tmp.writeText(j.toString() + "\n")
        tmp.renameTo(File(host, "progress.json"))
        if (log) appendLog(c, "install.log", "[$state $pct%] $step: $msg")
    }

    fun appendLog(c: Context, name: String, line: String) {
        runCatching {
            val f = File(logsDir(c).apply { mkdirs() }, name)
            if (f.length() > 2_000_000) f.renameTo(File(f.path + ".1"))
            f.appendText("[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $line\n")
        }
    }

    fun readJson(f: File): JSONObject? = runCatching { JSONObject(f.readText()) }.getOrNull()

    /** Тот же JSON, что `murf-local status`, когда Ubuntu ещё нет (или для быстрого ответа). */
    fun hostStatus(c: Context, installing: Boolean, running: Boolean): JSONObject = JSONObject()
        .put("version", runCatching { File(hostDir(c), "VERSION").readText().trim() }.getOrDefault(""))
        .put("embedded", true)
        .put("installed", installed(c))
        .put("running", running)
        .put("installing", installing)
        .put("proot_distro", false)
        .put("rootfs", rootfsReady(c))
        .put("port", 18080)
        .put("disk_mb", runCatching { File(hostDir(c), "disk.txt").readText().trim().toLong() }.getOrDefault(-1))
        .put("free_mb", freeMb(c))
        .put("termux_progress", readJson(File(hostDir(c), "progress.json")) ?: JSONObject.NULL)
        .put("progress", readJson(File(rootfsDir(c), "root/.murf/progress.json")) ?: JSONObject.NULL)
        .put("health", JSONObject.NULL)

    /** Размер rootfs (du без симлинков), пишется в host/disk.txt для статуса. */
    fun updateDiskUsage(c: Context) {
        val root = rootfsDir(c)
        if (!root.isDirectory) return
        var total = 0L
        runCatching {
            java.nio.file.Files.walk(root.toPath()).use { s ->
                s.forEach { p ->
                    if (java.nio.file.Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) total += runCatching { java.nio.file.Files.size(p) }.getOrDefault(0)
                }
            }
        }
        File(hostDir(c), "disk.txt").writeText((total / (1024 * 1024)).toString())
    }

    /** Полное удаление Ubuntu (права u+rwx перед удалением, симлинки не разыменовываем). */
    fun deleteRootfs(c: Context) {
        deleteTree(rootfsDir(c))
        for (n in listOf("progress.json", "disk.txt", "noseccomp")) File(hostDir(c), n).delete()
        File(baseDir(c), "fakeproc").deleteRecursively()
        File(baseDir(c), "tmp").deleteRecursively()
    }

    /**
     * ~/.hermes/trash внутри Ubuntu: setup.sh откладывает туда каталоги с .l2s-хардлинками
     * (кэш uv, окружения pm после неудачной попытки), которые изнутри proot не удаляются.
     */
    fun emptyTrash(c: Context) = deleteTree(File(rootfsDir(c), "root/.hermes/trash"))

    /** Рекурсивное удаление без перехода по символическим ссылкам (права каталогов чиним по пути). */
    fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                runCatching { TarExtractor.setMode(dir.toFile(), 0b111_000_000) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(file) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(file) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                runCatching { java.nio.file.Files.delete(dir) }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
