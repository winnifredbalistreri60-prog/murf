package com.uzairansar.hermex.agent

import android.content.Context
import com.uzairansar.hermex.ui.localization.AndroidLocalizationCatalog
import java.util.Locale

/**
 * Строки MURF: русский оригинал + английский перевод. Выбор по текущему языку приложения
 * (системный или выбранный в «Настройки → Язык»). Для прочих языков — английский.
 *
 * Если передан context, сначала ищет строку в AndroidLocalizationCatalog
 * (так мигрируют вызовы на localizedString).
 */
fun isRussianUi(): Boolean = Locale.getDefault().language == "ru"

fun tr(ru: String, en: String, context: Context? = null): String {
    val catalogEn = context?.let { AndroidLocalizationCatalog.resourceId(en)?.let { id -> it.getString(id) } }
    return if (isRussianUi()) {
        catalogEn ?: ru
    } else {
        en
    }
}
