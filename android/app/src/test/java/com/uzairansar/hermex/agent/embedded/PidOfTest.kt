package com.uzairansar.hermex.agent.embedded

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PidOfTest {
    @Test
    fun pidOfChildProcessPointsToThatProcess() {
        val p = ProcessBuilder("sleep", "5").start()
        try {
            val pid = pidOf(p)
            assertTrue("pid=$pid", pid > 0)
            assertTrue(File("/proc/$pid/cmdline").readText().startsWith("sleep"))
        } finally {
            p.destroy()
        }
    }
}
