package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MurfAvatarsTest {
    @Test fun encodeDecodeRoundTrip() {
        MurfHat.entries.forEach { h -> repeat(10) { c ->
            val s = MurfAvatarSpec(h, c); assertEquals(s, MurfAvatarSpec.decode(s.encode())); assert(s.encode().length <= 8)
        } }
    }
    @Test fun legacyEmojiFallsBackToBackendThenName() {
        assertNull(MurfAvatarSpec.decode("🤖"))
        assertEquals(MurfHat.Cap, MurfAvatarSpec.resolve("🦉", "codex", "x").hat)
        assertEquals(MurfAvatarSpec.byName("Помощник"), MurfAvatarSpec.resolve("🤖", "custom", "Помощник"))
        assertEquals(MurfAvatarSpec.byName("Помощник"), MurfAvatarSpec.byName(" помощник "))
        assertEquals(MurfHat.Beret, MurfAvatarSpec.resolve("k3-1", "hermes", "a").hat)
    }
}
