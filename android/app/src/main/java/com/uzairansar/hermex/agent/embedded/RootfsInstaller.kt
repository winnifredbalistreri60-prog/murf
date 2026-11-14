package com.uzairansar.hermex.agent.embedded

import com.uzairansar.hermex.agent.tr
import android.content.Context
import okhttp3.OkHttpClient
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Первая фаза установки встроенной среды: SHA256SUMS → ubuntu-base 24.04 (докачка, sha256) → распаковка
 * в filesDir/murf/rootfs → resolv.conf/hosts → маркер. Прогресс пишет в host/progress.json
 * (тот же формат, что у murf-local, поле termux_progress в статусе).
 */
class RootfsInstaller(
    private val context: Context,
    private val baseUrl: String = UbuntuBase.DEFAULT_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build(),
) {
    private fun progress(state: String, pct: Int, step: String, msg: String, log: Boolean = true) =
        EmbeddedRuntime.writeProgress(context, state, pct, step, msg, log)

    /** true — Ubuntu готова. Исключение — ошибка (прогресс уже помечен error). */
    fun run(cancelled: () -> Boolean = { false }) {
        val c = context
        if (EmbeddedRuntime.rootfsReady(c)) { progress("done", 4, "rootfs", tr("Ubuntu уже распакована", "Ubuntu is already unpacked")); return }
        val abi = EmbeddedRuntime.abi() ?: fail(1, "abi", tr("Нет proot для этого процессора", "No proot for this CPU"))
        val arch = UbuntuBase.archFor(abi) ?: fail(1, "abi", tr("ABI $abi не поддерживается", "ABI $abi is not supported"))
        val free = EmbeddedRuntime.freeMb(c)
        if (free in 0 until 4500) fail(0, "space", tr("Мало места: $free МБ, нужно ≥ 4,5 ГБ", "Not enough space: $free MB, need ≥ 4.5 GB"))

        progress("running", 1, "rootfs", tr("Список образов Ubuntu (cdimage.ubuntu.com)", "Ubuntu image list (cdimage.ubuntu.com)"))
        val sums = try {
            client.newCall(okhttp3.Request.Builder().url("$baseUrl/SHA256SUMS").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string()
            }
        } catch (e: Exception) { fail(1, "rootfs", tr("cdimage.ubuntu.com недоступен (нет интернета/VPN?): ${e.message}", "cdimage.ubuntu.com is unreachable (no internet or VPN?): ${e.message}")) }
        val img = UbuntuBase.pick(sums, arch) ?: fail(1, "rootfs", tr("не найден ubuntu-base $arch в SHA256SUMS", "ubuntu-base $arch not found in SHA256SUMS"))

        val tarball = File(EmbeddedRuntime.cacheDir(c), img.name)
        var loggedMb = -1L
        try {
            ResumableDownloader(client).download("$baseUrl/${img.name}", tarball, img.sha256, { done, total ->
                val mb = done / (1024 * 1024)
                val msg = if (total > 0) tr("Загрузка ${img.name}: $mb из ${total / (1024 * 1024)} МБ", "Downloading ${img.name}: $mb of ${total / (1024 * 1024)} MB") else tr("Загрузка ${img.name}: $mb МБ", "Downloading ${img.name}: $mb MB")
                val pct = if (total > 0) 1 + (done * 2 / total).toInt() else 2
                // progress.json — на каждый шаг, в журнал — раз в 5 МБ
                val logIt = mb / 5 != loggedMb / 5 || (total in 1..done)
                if (logIt) loggedMb = mb
                progress("running", pct.coerceIn(1, 3), "rootfs", msg, logIt)
            }, cancelled)
        } catch (e: ChecksumMismatchException) {
            fail(2, "rootfs", tr("контрольная сумма ${img.name} не совпала, повторите", "checksum of ${img.name} does not match, retry"))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(2, "rootfs", tr("не скачать ${img.name} (повтор продолжит загрузку): ${e.message}", "cannot download ${img.name} (retry resumes the download): ${e.message}"))
        }

        progress("running", 3, "rootfs", tr("Распаковка Ubuntu (${img.name})", "Unpacking Ubuntu (${img.name})"))
        val root = EmbeddedRuntime.rootfsDir(c)
        if (root.exists()) EmbeddedRuntime.deleteRootfs(c)
        root.mkdirs()
        val total = tarball.length().coerceAtLeast(1)
        var lastPct = -1
        try {
            CountingStream(tarball.inputStream().buffered(256 * 1024)).use { counting ->
                val st = TarExtractor(root) { it == "dev" || it.startsWith("dev/") }.extract(GZIPInputStream(counting, 64 * 1024)) {
                    if (cancelled()) throw java.util.concurrent.CancellationException("unpack cancelled")
                    val p = (counting.count * 100 / total).toInt()
                    if (p != lastPct && p % 5 == 0) { lastPct = p; progress("running", 3, "rootfs", tr("Распаковка Ubuntu: $p%", "Unpacking Ubuntu: $p%"), p % 25 == 0) }
                }
                EmbeddedRuntime.appendLog(c, "install.log", "распаковано: $st")
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            EmbeddedRuntime.deleteRootfs(c)
            fail(3, "rootfs", tr("не удалось распаковать Ubuntu: ${e.message}", "cannot unpack Ubuntu: ${e.message}"))
        }
        if (!File(root, "usr/bin/bash").exists()) fail(3, "rootfs", tr("после распаковки нет /usr/bin/bash", "no /usr/bin/bash after unpacking"))
        File(root, "dev").mkdirs(); File(root, "tmp").mkdirs(); File(root, "root").mkdirs()
        // apt внутри proot: без отдельного пользователя _apt (setgroups/seteuid эмулируются частично)
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/99murf-proot").writeText("APT::Sandbox::User \"root\";\n")
        File(root, EmbeddedRuntime.ROOTFS_MARKER).writeText(img.name + "\n")
        EmbeddedRuntime.writeResolvConf(c)
        tarball.delete() // ~30 МБ больше не нужны
        progress("done", 4, "rootfs", tr("Ubuntu распакована (${img.name})", "Ubuntu unpacked (${img.name})"))
    }

    private fun fail(pct: Int, step: String, msg: String): Nothing {
        progress("error", pct, step, msg)
        throw RootfsInstallException(msg)
    }

    private class CountingStream(inner: InputStream) : FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}

class RootfsInstallException(msg: String) : Exception(msg)
