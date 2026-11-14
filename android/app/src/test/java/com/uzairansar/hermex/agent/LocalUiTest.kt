package com.uzairansar.hermex.agent

import com.uzairansar.hermex.agent.LocalUi.InstallView
import com.uzairansar.hermex.agent.LocalUi.RunView
import com.uzairansar.hermex.agent.embedded.LocalRuntimeService.Phase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class LocalUiTest {
    private lateinit var saved: Locale
    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved) }

    @Test
    fun serviceErrorWinsOverStaleRunningProgress() {
        // служба уже ERROR, а status ещё «running 6%»: сразу экран ошибки, без «Installing: 6%»
        assertEquals(InstallView.FAILED, LocalUi.installView(true, Phase.ERROR, true, "running", false, false))
        assertEquals(InstallView.RUNNING, LocalUi.installView(true, Phase.INSTALLING, true, "error", false, false))
    }

    @Test
    fun finishedInstallHidesStaleProgress() {
        assertEquals(InstallView.NONE, LocalUi.installView(true, Phase.IDLE, true, "running", false, true))
        assertEquals(InstallView.NONE, LocalUi.installView(true, Phase.IDLE, false, "done", false, true))
        // старт служб упал — это не ошибка установки
        assertEquals(InstallView.NONE, LocalUi.installView(true, Phase.ERROR, false, "done", false, true))
    }

    @Test
    fun interruptedInstallAfterAppRestartIsFailure() {
        assertEquals(InstallView.FAILED, LocalUi.installView(true, Phase.IDLE, false, "running", false, false))
        assertEquals(InstallView.FAILED, LocalUi.installView(true, Phase.IDLE, false, "error", false, false))
    }

    @Test
    fun termuxKeepsStatusBasedLogic() {
        assertEquals(InstallView.FAILED, LocalUi.installView(false, Phase.IDLE, false, "error", false, false))
        assertEquals(InstallView.RUNNING, LocalUi.installView(false, Phase.IDLE, false, "error", true, false))
        assertEquals(InstallView.RUNNING, LocalUi.installView(false, Phase.IDLE, false, "running", false, false))
        assertEquals(InstallView.NONE, LocalUi.installView(false, Phase.IDLE, false, "done", false, true))
    }

    @Test
    fun runViewFollowsServiceNotStaleStatus() {
        assertEquals(RunView.STOPPING, LocalUi.runView(true, Phase.STOPPING, true, true))
        assertEquals(RunView.STOPPED, LocalUi.runView(true, Phase.IDLE, false, true))
        assertEquals(RunView.RUNNING, LocalUi.runView(true, Phase.RUNNING, true, false))
        assertEquals(RunView.STARTING, LocalUi.runView(true, Phase.STARTING, false, false))
        assertEquals(RunView.RUNNING, LocalUi.runView(false, Phase.IDLE, false, true))
    }

    @Test
    fun bannerClearsWhenInstallLeavesInstalling() {
        assertFalse(LocalUi.clearInstallBanner(true, false, Phase.IDLE, "", false)) // служба ещё не начала
        assertFalse(LocalUi.clearInstallBanner(true, true, Phase.INSTALLING, "running", true))
        assertTrue(LocalUi.clearInstallBanner(true, true, Phase.IDLE, "running", false))
        assertTrue(LocalUi.clearInstallBanner(true, true, Phase.ERROR, "running", false))
        assertTrue(LocalUi.clearInstallBanner(false, false, Phase.IDLE, "done", false))
        assertFalse(LocalUi.clearInstallBanner(false, false, Phase.IDLE, "running", true))
    }

    @Test
    fun webuiReadyNeedsWebuiFlag() {
        assertFalse(LocalUi.webuiReady(null))
        assertFalse(LocalUi.webuiReady(JSONObject("""{"ok":true,"webui":false}""")))
        assertTrue(LocalUi.webuiReady(JSONObject("""{"ok":true,"webui":true}""")))
    }

    @Test
    fun updateHasOwnBannerText() {
        assertTrue(LocalUi.installBanner(true, false).contains("20–60"))
        assertFalse(LocalUi.installBanner(true, true).contains("20–60"))
        assertTrue(LocalUi.installBanner(true, true).startsWith("Update started"))
        Locale.setDefault(Locale.forLanguageTag("ru"))
        assertTrue(LocalUi.installBanner(true, true).startsWith("Обновление запущено"))
    }
}
