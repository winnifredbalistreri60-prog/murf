package com.uzairansar.hermex.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStepsTest {
    private fun cyr(s: String) = s.any { it in 'а'..'я' || it in 'А'..'Я' }

    @Test
    fun englishAptProgressKeepsNumbers() {
        val t = InstallSteps.running("apt", "Пакеты: загружено 120 МБ, установлено 40 из 312", ru = false)
        assertEquals("System packages: 120 MB downloaded, 40 of 312 installed", t)
    }

    @Test
    fun englishHermesStage() {
        assertEquals("Hermes Agent: installer stage products", InstallSteps.running("hermes", "Hermes Agent: стадия products", ru = false))
    }

    @Test
    fun englishNeverShowsCyrillicForKnownSteps() {
        val samples = listOf(
            "rootfs" to "Распаковка Ubuntu: 20%", "rootfs" to "Ubuntu уже распакована", "apt" to "Настройка apt",
            "apt" to "Пакеты установлены", "hermes" to "Hermes Agent: установка (git clone + Python-зависимости, 5–20 мин)",
            "webui" to "Hermes WebUI: загрузка (как на сервере)", "config" to "Настройка Hermes", "done" to "Локальная среда установлена",
            "copy" to "Копирование скриптов MURF в Ubuntu", "unknown-step" to "что-то по-русски",
        )
        for ((step, msg) in samples) assertFalse("$step: $msg", cyr(InstallSteps.running(step, msg, ru = false)))
    }

    @Test
    fun englishMessagesFromAppPassThrough() {
        assertEquals("Downloading x.tar.gz: 3 of 28 MB", InstallSteps.running("rootfs", "Downloading x.tar.gz: 3 of 28 MB", ru = false))
    }

    @Test
    fun russianKeepsScriptText() {
        assertEquals("Распаковка Ubuntu: 20%", InstallSteps.running("rootfs", "Распаковка Ubuntu: 20%", ru = true))
        assertEquals("Системные пакеты (apt)…", InstallSteps.running("apt", "", ru = true))
    }

    @Test
    fun errorTextsAreLocalized() {
        val en = InstallSteps.error("hermes", 35, "установщик Hermes: стадия products завершилась с ошибкой (см. журнал)", ru = false)
        assertEquals("Failed at “Hermes Agent” (35%): installer stage products failed", en)
        assertFalse(cyr(InstallSteps.error("rootfs", 1, "cdimage.ubuntu.com недоступен (нет интернета/VPN?): x", ru = false)))
        assertTrue(InstallSteps.error("apt", 5, "apt-get install не прошёл", ru = true).startsWith("Ошибка на шаге «Системные пакеты (apt)» (5%)"))
    }

    @Test
    fun staleInnerErrorIsHiddenByNewerOuterProgress() {
        val j = JSONObject()
            .put("termux_progress", JSONObject().put("state", "done").put("pct", 5).put("step", "inner").put("msg", "x").put("ts", 2000))
            .put("progress", JSONObject().put("state", "error").put("pct", 35).put("step", "hermes").put("msg", "old").put("ts", 1000))
        val s = LocalEnv.parseStatus(j)
        assertEquals("inner", s.step)
        val j2 = JSONObject(j.toString()).put("progress", JSONObject().put("state", "running").put("pct", 30).put("step", "apt").put("msg", "new").put("ts", 3000))
        assertEquals("apt", LocalEnv.parseStatus(j2).step)
    }
}
