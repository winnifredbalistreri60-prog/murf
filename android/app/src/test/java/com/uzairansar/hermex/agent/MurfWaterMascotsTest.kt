package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MurfWaterMascotsTest {
    @Test fun everyHatHasSmallBundledClipAndStill() {
        assertEquals(MurfHat.entries.size, MurfWaterMascots.ids.size)
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        MurfHat.entries.forEach { h ->
            val clip = File(root, MurfWaterMascots.clipAsset(h))
            val still = File(root, MurfWaterMascots.stillAsset(h))
            assertTrue("${clip.name} missing", clip.isFile)
            assertTrue("${still.name} missing", still.isFile)
            assertTrue("${clip.name} too big", clip.length() < 300_000)
        }
    }

    @Test fun onlyFirstThreeRequestsPlay() {
        val t = List(5) { Any() }
        t.forEach(MurfWaterMascots::request)
        assertEquals(listOf(true, true, true, false, false), t.map(MurfWaterMascots::granted))
        MurfWaterMascots.drop(t[0])
        assertTrue(MurfWaterMascots.granted(t[3]))
        t.forEach(MurfWaterMascots::drop)
    }
}
