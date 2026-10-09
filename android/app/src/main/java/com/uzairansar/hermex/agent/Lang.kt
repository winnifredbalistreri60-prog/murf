package com.uzairansar.hermex.agent

import java.util.Locale

/**
 * Строки MURF: русский оригинал + английский перевод. Выбор по текущему языку приложения
 * (системный или выбранный в «Настройки → Язык»). Для прочих языков — английский.
 */
fun isRussianUi(): Boolean = Locale.getDefault().language == "ru"

fun tr(ru: String, en: String): String = if (isRussianUi()) ru else en
