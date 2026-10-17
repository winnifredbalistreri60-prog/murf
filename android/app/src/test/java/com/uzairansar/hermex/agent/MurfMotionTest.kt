package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MurfMotionTest {
    @Test fun reduceMotionWhenUserOffOrSystemOff() {
        assertFalse(MurfMotion.reduceMotion(userEnabled = true, systemReduced = false))
        assertTrue(MurfMotion.reduceMotion(userEnabled = false, systemReduced = false))
        assertTrue(MurfMotion.reduceMotion(userEnabled = true, systemReduced = true))
    }

    @Test fun specsDisabledUnderReduceMotion() {
        assertNull(MurfMotion.itemFadeIn(true))
        assertNull(MurfMotion.itemPlacement(true))
        assertTrue(MurfMotion.itemFadeIn(false) != null)
    }

    @Test fun elapsedFormat() {
        assertEquals("0:00", formatElapsed(-5))
        assertEquals("0:42", formatElapsed(42_000))
        assertEquals("12:05", formatElapsed(725_000))
        assertEquals("1:02:05", formatElapsed(3_725_000))
    }
}
