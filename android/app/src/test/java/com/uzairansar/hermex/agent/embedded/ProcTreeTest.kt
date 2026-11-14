package com.uzairansar.hermex.agent.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parsesPpidEvenWithSpacesAndParensInComm() {
        assertEquals(42, ProcTree.parsePpid("100 (my (weird) proc) S 42 100 100 0 -1"))
        assertEquals(1, ProcTree.parsePpid("7 (bash) R 1 7 7"))
    }

    @Test
    fun findsAllDescendantsFromFakeProc() {
        val proc = tmp.newFolder("proc")
        fun p(pid: Int, ppid: Int) = java.io.File(proc, "$pid").apply { mkdirs() }
            .resolve("stat").writeText("$pid (x) S $ppid 0 0")
        p(10, 1); p(11, 10); p(12, 11); p(13, 11); p(20, 1); p(21, 20)
        java.io.File(proc, "self").mkdirs()
        assertEquals(setOf(11, 12, 13), ProcTree.descendants(10, proc).toSet())
        assertTrue(ProcTree.descendants(99, proc).isEmpty())
    }
}
