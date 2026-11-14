package com.uzairansar.hermex.agent.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {
    private val l = ProotLayout(
        nativeLibDir = "/data/app/~~x/ru.dredd20.agent-1/lib/arm64",
        rootfs = "/data/user/0/ru.dredd20.agent/files/murf/rootfs",
        hostDir = "/data/user/0/ru.dredd20.agent/files/murf/host",
        tmpDir = "/data/user/0/ru.dredd20.agent/files/murf/tmp",
        fakeProcDir = "/data/user/0/ru.dredd20.agent/files/murf/fakeproc",
    )

    @Test
    fun argsStartWithProotFromNativeLibDirAndKeySwitches() {
        val a = ProotCommand.args(l, listOf("/bin/true"))
        assertEquals("${l.nativeLibDir}/libproot.so", a[0])
        assertTrue(a.containsAll(listOf("--link2symlink", "--kill-on-exit", "-0")))
        val r = a.indexOf("-r"); assertEquals(l.rootfs, a[r + 1])
        assertEquals(listOf("-w", "/root", "/bin/true"), a.takeLast(3))
    }

    @Test
    fun bindsDevProcSysShmAndHostDir() {
        val a = ProotCommand.args(l, listOf("/bin/true"))
        val binds = a.zipWithNext().filter { it.first == "-b" }.map { it.second }
        assertTrue(binds.containsAll(listOf("/dev", "/proc", "/sys", "${l.rootfs}/tmp:/dev/shm", "${l.hostDir}:/murf-host")))
    }

    @Test
    fun fakeProcFilesAreBoundOverProc() {
        val a = ProotCommand.args(l, listOf("/bin/true"), listOf("stat", "uptime"))
        val binds = a.zipWithNext().filter { it.first == "-b" }.map { it.second }
        assertTrue(binds.contains("${l.fakeProcDir}/stat:/proc/stat"))
        assertTrue(binds.contains("${l.fakeProcDir}/uptime:/proc/uptime"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFakeProcRejected() { ProotCommand.args(l, listOf("/bin/true"), listOf("../etc/passwd")) }

    @Test
    fun envHasLoaderTmpAndOptionalFlags() {
        val e = ProotCommand.env(l, noSeccomp = false, hasLoader32 = true)
        assertEquals("${l.nativeLibDir}/libproot-loader.so", e["PROOT_LOADER"])
        assertEquals("${l.nativeLibDir}/libproot-loader32.so", e["PROOT_LOADER_32"])
        assertEquals(l.tmpDir, e["PROOT_TMP_DIR"])
        assertFalse(e.containsKey("PROOT_NO_SECCOMP"))
        assertFalse(e.containsKey("LD_PRELOAD"))
        val e2 = ProotCommand.env(l, noSeccomp = true, hasLoader32 = false)
        assertEquals("1", e2["PROOT_NO_SECCOMP"]); assertFalse(e2.containsKey("PROOT_LOADER_32"))
    }

    @Test
    fun murfLocalRunsScriptFromHostMountInEmbeddedMode() {
        val c = ProotCommand.murfLocal(listOf("status"), mapOf("MURF_ANDROID_INFO" to "Android 16"))
        assertEquals(listOf("/usr/bin/env", "-i"), c.take(2))
        assertTrue(c.contains("MURF_EMBEDDED=1"))
        assertTrue(c.contains("MURF_HOST_DIR=/murf-host"))
        assertTrue(c.contains("MURF_ANDROID_INFO=Android 16"))
        assertEquals(listOf("/bin/bash", "/murf-host/murf-local", "status"), c.takeLast(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badEnvNameRejected() { ProotCommand.murfLocal(listOf("x"), mapOf("A;rm -rf" to "1")) }

    @Test
    fun resolvConfUsesNetworkDnsThenFallbacks() {
        val r = ProotCommand.resolvConf(listOf("10.0.2.3", "fe80::1%wlan0", "8.8.8.8"))
        val ns = r.lines().filter { it.startsWith("nameserver") }
        assertEquals(listOf("nameserver 10.0.2.3", "nameserver fe80::1", "nameserver 8.8.8.8", "nameserver 1.1.1.1"), ns)
        assertEquals(listOf("nameserver 1.1.1.1", "nameserver 8.8.8.8"), ProotCommand.resolvConf(emptyList()).lines().filter { it.startsWith("nameserver") })
    }

    @Test
    fun fakeProcContentLooksLikeProc() {
        assertTrue(ProotCommand.fakeProcContent("version", "6.1.0").startsWith("Linux version 6.1.0"))
        assertTrue(ProotCommand.fakeProcContent("stat", "x").startsWith("cpu "))
        for (f in ProotCommand.FAKE_PROC) assertTrue(ProotCommand.fakeProcContent(f, "k").endsWith("\n"))
    }
}
