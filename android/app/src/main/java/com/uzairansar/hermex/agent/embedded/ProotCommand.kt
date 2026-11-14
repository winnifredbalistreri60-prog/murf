package com.uzairansar.hermex.agent.embedded

/**
 * Пути встроенной среды. Всё, кроме [nativeLibDir], лежит в данных приложения (filesDir/murf).
 * [nativeLibDir] — каталог распакованных lib*.so (exec оттуда разрешён на targetSdk 29+).
 */
data class ProotLayout(
    val nativeLibDir: String,
    val rootfs: String,
    val hostDir: String,
    val tmpDir: String,
    val fakeProcDir: String,
) {
    val proot get() = "$nativeLibDir/libproot.so"
    val loader get() = "$nativeLibDir/libproot-loader.so"
    val loader32 get() = "$nativeLibDir/libproot-loader32.so"
}

/** Сборка командной строки и окружения proot (чистая логика, покрыта юнит-тестами). */
object ProotCommand {
    /** Куда внутри Ubuntu монтируется каталог состояния приложения (скрипты, логи, progress.json). */
    const val HOST_MOUNT = "/murf-host"
    const val GUEST_PATH = "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Файлы /proc, которые Android (SELinux) часто не даёт читать приложениям: подменяем. */
    val FAKE_PROC = listOf("loadavg", "stat", "uptime", "version", "vmstat")

    /**
     * Как `raw_proot` из murf-local: fake root (-0), link2symlink (хардлинки в данных приложения запрещены),
     * kill-on-exit, /dev /proc /sys, /dev/shm в rootfs/tmp, каталог приложения в [HOST_MOUNT].
     */
    fun args(l: ProotLayout, guest: List<String>, fakeProcFiles: List<String> = emptyList(), cwd: String = "/root"): List<String> {
        require(guest.isNotEmpty()) { "empty guest command" }
        val a = mutableListOf(
            l.proot, "--link2symlink", "--kill-on-exit", "-0",
            "-r", l.rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${l.rootfs}/tmp:/dev/shm",
            "-b", "${l.hostDir}:$HOST_MOUNT",
        )
        for (f in fakeProcFiles) {
            require(f in FAKE_PROC) { "unexpected fake /proc file $f" }
            a += listOf("-b", "${l.fakeProcDir}/$f:/proc/$f")
        }
        a += listOf("-w", cwd)
        a += guest
        return a
    }

    /** Окружение процесса proot (окружение Android не наследуем: LD_PRELOAD и пр. не нужны). */
    fun env(l: ProotLayout, noSeccomp: Boolean, hasLoader32: Boolean): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", l.tmpDir)
        put("PROOT_LOADER", l.loader)
        if (hasLoader32) put("PROOT_LOADER_32", l.loader32)
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
        put("HOME", "/root")
        put("LANG", "C.UTF-8")
        put("TERM", "xterm-256color")
        put("TMPDIR", "/tmp")
        put("PATH", GUEST_PATH)
    }

    /** `murf-local <args>` внутри Ubuntu в встроенном режиме (чистое окружение, как у inner()). */
    fun murfLocal(args: List<String>, extraEnv: Map<String, String> = emptyMap()): List<String> {
        val e = mutableListOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "LANG=C.UTF-8", "TERM=xterm", "PATH=$GUEST_PATH",
            "MURF_EMBEDDED=1", "MURF_HOST_DIR=$HOST_MOUNT",
        )
        for ((k, v) in extraEnv) {
            require(k.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "bad env name $k" }
            e += "$k=$v"
        }
        return e + listOf("/bin/bash", "$HOST_MOUNT/murf-local") + args
    }

    fun fakeProcContent(name: String, kernelRelease: String): String = when (name) {
        "loadavg" -> "0.12 0.07 0.02 2/165 765\n"
        "uptime" -> "124.08 932.80\n"
        "version" -> "Linux version $kernelRelease (proot@murf) #1 SMP PREEMPT\n"
        "stat" -> "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n"
        "vmstat" -> "nr_free_pages 136757\nnr_inactive_anon 0\nnr_active_anon 0\n"
        else -> throw IllegalArgumentException(name)
    }

    /** resolv.conf для Ubuntu: DNS текущей сети Android + запасные публичные. */
    fun resolvConf(dns: List<String>): String {
        val all = (dns.map { it.trim().substringBefore('%') }.filter { it.isNotEmpty() } + listOf("1.1.1.1", "8.8.8.8")).distinct()
        return "# создано MURF (встроенный режим)\n" + all.take(4).joinToString("") { "nameserver $it\n" }
    }
}
