package com.uzairansar.hermex.agent

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Язык приложения: «Как в системе» или любой из языков, которые есть в APK (res/xml/locales_config.xml).
 * Android 13+: системный per-app language (LocaleManager) — язык виден и в «Настройки → Приложения → Язык».
 * Android 8–12: сохраняем в SharedPreferences и подменяем конфигурацию в MainActivity.attachBaseContext.
 */
object AppLanguage {
    /** Теги в том же виде, что в locales_config.xml. "" = как в системе. */
    val TAGS = listOf(
        "", "en", "ru", "ar", "de", "es", "fr", "iw", "it", "ja", "ko", "nl", "pl", "pt-BR", "tr", "ur",
        "zh-Hans", "zh-Hant", "zh-HK",
    )
    private const val PREFS = "murf_lang"
    private const val KEY = "tag"

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(LocaleManager::class.java)
            val list = lm?.applicationLocales
            return if (list == null || list.isEmpty) "" else normalize(list[0].toLanguageTag())
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    private fun normalize(tag: String): String = when {
        TAGS.contains(tag) -> tag
        tag.startsWith("he") || tag.startsWith("iw") -> "iw"
        tag == "zh-CN" || tag.startsWith("zh-Hans") -> "zh-Hans"
        tag == "zh-TW" || tag.startsWith("zh-Hant-TW") -> "zh-Hant"
        tag.startsWith("zh-HK") || tag.startsWith("zh-Hant-HK") -> "zh-HK"
        tag.startsWith("pt") -> "pt-BR"
        else -> TAGS.firstOrNull { it.isNotEmpty() && tag.substringBefore('-') == it.substringBefore('-') } ?: tag
    }

    /** Название языка на нём самом («Русский», «English», «Deutsch»…). */
    fun nativeName(tag: String): String {
        if (tag.isEmpty()) return tr("Как в системе", "System default")
        val loc = Locale.forLanguageTag(tag)
        val name = loc.getDisplayName(loc).ifBlank { tag }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
    }

    fun set(activity: Activity, tag: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            // Система сама пересоздаёт Activity с новым языком.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            if (tag.isEmpty()) Locale.setDefault(systemLocale()) else Locale.setDefault(Locale.forLanguageTag(tag))
            activity.recreate()
        }
    }

    private fun systemLocale(): Locale =
        android.content.res.Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()

    /** Android < 13: обернуть контекст Activity выбранным языком. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return ContextWrapper(base.createConfigurationContext(config))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Строка «Язык / Language» для настроек + диалог выбора. */
@Composable
fun LanguageSettingsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = remember { AppLanguage.current(context) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable { open = true }
            .testTag("language_row"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(com.uzairansar.hermex.R.drawable.ic_lucide_languages), contentDescription = null)
        Text(tr("Язык / Language", "Language / Язык"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(AppLanguage.nativeName(current), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("Язык приложения", "App language")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(AppLanguage.TAGS) { tag ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    if (tag != current) context.findActivity()?.let { AppLanguage.set(it, tag) }
                                }
                                .padding(vertical = 2.dp)
                                .testTag("lang_" + tag.ifEmpty { "system" }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(AppLanguage.nativeName(tag))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(tr("Закрыть", "Close")) } },
        )
    }
}
