package com.uzairansar.hermex.agent

import com.uzairansar.hermex.agent.embedded.LocalRuntimeService.Phase
import org.json.JSONObject

/**
 * Чистая логика экрана «Среда на телефоне» (юнит-тесты: LocalUiTest).
 * Во встроенном режиме источник истины — фаза службы [Phase] (меняется сразу), а `murf-local status`
 * опрашивается раз в несколько секунд и может отставать: отсюда были гонки «Installing: 6%» после
 * ошибки и зелёные «Running/WebUI» после «Стоп».
 */
object LocalUi {
    enum class InstallView { NONE, RUNNING, FAILED }

    /**
     * @param embedded встроенный режим (служба MURF), иначе Termux
     * @param phase фаза службы (встроенный режим)
     * @param installOp фаза получена установкой/обновлением ([LocalRuntimeService.State.installOp])
     * @param stepState state последнего прогресса из status ("running"/"done"/"error")
     * @param statusInstalling status.installing
     * @param installed среда установлена
     */
    fun installView(embedded: Boolean, phase: Phase, installOp: Boolean, stepState: String, statusInstalling: Boolean, installed: Boolean): InstallView {
        if (!embedded) return when {
            stepState == "error" && !statusInstalling -> InstallView.FAILED
            statusInstalling || stepState == "running" -> InstallView.RUNNING
            else -> InstallView.NONE
        }
        return when {
            phase == Phase.INSTALLING -> InstallView.RUNNING
            phase == Phase.ERROR && installOp -> InstallView.FAILED
            // только что успешно завершилась (или отменена) — устаревший "running" из status не показываем
            installOp && (phase == Phase.IDLE || phase == Phase.STOPPING) -> InstallView.NONE
            stepState == "error" -> InstallView.FAILED
            // установка прервана (MURF убит посреди установки): прогресс "running", но службы установки нет
            stepState == "running" && !installed -> InstallView.FAILED
            else -> InstallView.NONE
        }
    }

    enum class RunView { STOPPED, STARTING, RUNNING, STOPPING }

    /** Встроенный режим: «работает» = жив процесс службы; status (с health) может отставать на цикл опроса. */
    fun runView(embedded: Boolean, phase: Phase, serviceAlive: Boolean, statusRunning: Boolean): RunView {
        if (!embedded) return if (statusRunning) RunView.RUNNING else RunView.STOPPED
        return when {
            phase == Phase.STOPPING -> RunView.STOPPING
            phase == Phase.STARTING -> RunView.STARTING
            serviceAlive -> RunView.RUNNING
            else -> RunView.STOPPED
        }
    }

    /**
     * Баннер «Установка запущена… 20–60 мин» снимаем, как только установка ушла из INSTALLING
     * (успех, ошибка, отмена). [sawInstalling]: служба уже успела перейти в INSTALLING после нажатия.
     */
    fun clearInstallBanner(embedded: Boolean, sawInstalling: Boolean, phase: Phase, stepState: String, statusInstalling: Boolean): Boolean =
        if (embedded) sawInstalling && phase != Phase.INSTALLING
        else !statusInstalling && (stepState == "error" || stepState == "done")

    /** Можно входить в WebUI: health отвечает и WebUI уже поднят (health 200 приходит раньше WebUI). */
    fun webuiReady(health: JSONObject?): Boolean = health != null && health.optBoolean("webui", false)

    fun installBanner(embedded: Boolean, update: Boolean): String = when {
        update && embedded -> tr("Обновление запущено в службе MURF: проверка пакетов Ubuntu и Hermes Agent, обычно несколько минут. Прогресс и журнал здесь.",
            "Update started in the MURF service: checks Ubuntu packages and Hermes Agent, usually a few minutes. Progress and log are here.")
        update -> tr("Обновление запущено в фоне (без окна Termux), обычно несколько минут. Прогресс и журнал здесь.",
            "Update started in the background (no Termux window), usually a few minutes. Progress and log are here.")
        embedded -> tr("Установка запущена в службе MURF (уведомление «Локальная среда»). Сначала загрузка Ubuntu (~30 МБ), потом пакеты: 20–60 мин и ~4 ГБ. Прогресс и журнал здесь.",
            "Installation started in the MURF service (“Local environment” notification). First Ubuntu (~30 MB), then packages: 20–60 min and ~4 GB. Progress and log are here.")
        else -> tr("Установка запущена в фоне (без окна Termux): 20–60 мин и ~4 ГБ. Прогресс и журнал остаются в MURF.",
            "Installation started in the background (no Termux window). Takes 20–60 min and ~4 GB — progress and log stay in MURF.")
    }
}
