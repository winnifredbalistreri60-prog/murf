package com.uzairansar.hermex.agent

/**
 * Текст шага установки для экрана. Скрипты (setup.sh, murf-local) пишут прогресс по-русски;
 * в английском интерфейсе показываем перевод по ключу шага, а числа (МБ, проценты, пакеты, стадия
 * Hermes) достаём из исходного сообщения. Неизвестный шаг: исходный текст (лучше, чем ничего).
 */
object InstallSteps {
    private val dlRu = Regex("""(\d+) из (\d+) МБ""")
    private val dlOnly = Regex("""Загрузка \S+: (\d+) МБ""")
    private val unpack = Regex("""Распаковка Ubuntu: (\d+)%""")
    private val apt = Regex("""загружено (\d+) МБ, установлено (\d+) из (\S+)""")
    private val stage = Regex("""стадия ([a-z-]+)""")

    /** Название шага по ключу (для ошибок и как запасной текст). */
    fun title(step: String, ru: Boolean): String {
        val (r, e) = when (step) {
            "abi" -> "Проверка процессора" to "CPU check"
            "space" -> "Проверка места" to "Free space check"
            "rootfs" -> "Ubuntu (базовая система)" to "Ubuntu base system"
            "termux" -> "Termux" to "Termux"
            "copy" -> "Копирование скриптов MURF" to "Copying MURF scripts"
            "inner" -> "Установка внутри Ubuntu" to "Installing inside Ubuntu"
            "apt" -> "Системные пакеты (apt)" to "System packages (apt)"
            "hermes" -> "Hermes Agent" to "Hermes Agent"
            "browser" -> "Chromium для агента" to "Chromium for the agent"
            "webui" -> "Hermes WebUI" to "Hermes WebUI"
            "config" -> "Настройка Hermes" to "Hermes configuration"
            "webui-deps" -> "Зависимости WebUI" to "WebUI dependencies"
            "dsh" -> "DeepSeek Harness" to "DeepSeek Harness"
            "done" -> "Готово" to "Done"
            else -> step to step
        }
        return if (ru) r else e
    }

    /** Текст шага в процессе установки. */
    fun running(step: String, msg: String, ru: Boolean): String {
        if (ru) return msg.ifBlank { title(step, true) + "…" }
        if (msg.isNotBlank() && msg.isAsciiText()) return msg  // уже по-английски (сообщения приложения)
        dlRu.find(msg)?.let { if (step == "rootfs") return "Downloading Ubuntu: ${it.groupValues[1]} of ${it.groupValues[2]} MB" }
        dlOnly.find(msg)?.let { if (step == "rootfs") return "Downloading Ubuntu: ${it.groupValues[1]} MB" }
        unpack.find(msg)?.let { return "Unpacking Ubuntu: ${it.groupValues[1]}%" }
        apt.find(msg)?.let { return "System packages: ${it.groupValues[1]} MB downloaded, ${it.groupValues[2]} of ${it.groupValues[3]} installed" }
        if (step == "hermes") stage.find(msg)?.let { return "Hermes Agent: installer stage ${it.groupValues[1]}" }
        return when (step) {
            "rootfs" -> when {
                msg.contains("уже распакована") -> "Ubuntu is already unpacked"
                msg.contains("распакована") -> "Ubuntu unpacked"
                msg.contains("Распаковка") -> "Unpacking Ubuntu"
                else -> "Ubuntu base system: download"
            }
            "apt" -> when {
                msg.contains("update") -> "apt-get update: Ubuntu package lists"
                msg.contains("установлены") -> "System packages installed"
                else -> "System packages (XFCE desktop, VNC, nginx, python): the longest step"
            }
            "hermes" -> when {
                msg.contains("установлен") && !msg.contains("установка") -> "Hermes Agent installed"
                msg.contains("загрузка установщика") -> "Hermes Agent: downloading the installer"
                else -> "Hermes Agent: git clone + Python dependencies (5–20 min)"
            }
            "inner" -> "Installing inside Ubuntu"
            "copy" -> "Copying MURF scripts into Ubuntu"
            "browser" -> "Chromium for the agent (optional)"
            "webui" -> "Hermes WebUI: download"
            "config" -> "Configuring Hermes (local keys, 127.0.0.1)"
            "webui-deps" -> "WebUI: Python dependencies"
            "dsh" -> "DeepSeek Harness: Node 22 + dsh (optional)"
            "done" -> "Local environment installed"
            else -> if (msg.isAsciiText()) msg else title(step, false)
        }
    }

    /** Текст ошибки: шаг + подсказка. В английском интерфейсе русский текст ошибки не показываем. */
    fun error(step: String, pct: Int, msg: String, ru: Boolean): String {
        val t = title(step.ifBlank { "?" }, ru)
        return if (ru) "Ошибка на шаге «$t» ($pct%): $msg"
        else {
            val detail = when {
                step == "rootfs" && msg.contains("недоступен") -> ": cdimage.ubuntu.com is unreachable (no internet or VPN?)"
                step == "space" -> ": not enough free space (need ≥ 4.5 GB)"
                msg.contains("стадия ") -> stage.find(msg)?.let { ": installer stage ${it.groupValues[1]} failed" } ?: ""
                msg.isAsciiText() && msg.isNotBlank() -> ": $msg"
                else -> ""
            }
            "Failed at “$t” ($pct%)$detail"
        }
    }

    private fun String.isAsciiText() = all { it.code < 128 }
}
