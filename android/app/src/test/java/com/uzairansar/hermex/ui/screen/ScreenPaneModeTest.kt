package com.uzairansar.hermex.ui.screen

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenPaneModeTest {
    @Test
    fun mapsWindowWidthClasses() {
        assertEquals(ScreenPaneMode.Compact, screenPaneModeFor(WindowWidthSizeClass.Compact))
        assertEquals(ScreenPaneMode.Medium, screenPaneModeFor(WindowWidthSizeClass.Medium))
        assertEquals(ScreenPaneMode.Expanded, screenPaneModeFor(WindowWidthSizeClass.Expanded))
    }

    @Test
    fun narrowChatAreaInExpandedWindowFallsBackToSheet() {
        assertEquals(ScreenPaneMode.Medium, effectiveScreenPaneMode(ScreenPaneMode.Expanded, 500))
        assertEquals(ScreenPaneMode.Expanded, effectiveScreenPaneMode(ScreenPaneMode.Expanded, 940))
        assertEquals(ScreenPaneMode.Expanded, effectiveScreenPaneMode(ScreenPaneMode.Expanded, 0)) // ещё не измерено
        assertEquals(ScreenPaneMode.Compact, effectiveScreenPaneMode(ScreenPaneMode.Compact, 500))
    }

    @Test
    fun splitFractionIsClamped() {
        assertEquals(0.3f, clampChatSplit(0.05f))
        assertEquals(0.75f, clampChatSplit(0.99f))
        assertEquals(0.5f, clampChatSplit(0.5f))
    }
}
