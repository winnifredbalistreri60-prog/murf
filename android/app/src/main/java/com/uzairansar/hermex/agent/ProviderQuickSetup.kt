package com.uzairansar.hermex.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Компактный путь «ключ → живые модели с API → выбор» для онбординга / Harness.
 * Ключ только в EncryptedSharedPreferences; список моделей — [MurfProviders.fetchModels].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderQuickSetup(
    modifier: Modifier = Modifier,
    /** Тёмная палитра онбординга. */
    onboardingStyle: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ver by remember { mutableIntStateOf(0) }
    val selected = remember(ver) { MurfProviders.selected(context) }
    val existing = remember(ver) {
        selected?.first?.let { id -> MurfProviders.list(context).firstOrNull { it.id == id } }
            ?: MurfProviders.list(context).firstOrNull()
    }
    val cachedModels = existing?.models.orEmpty()
    val fetchedAtLabel = existing?.modelsFetchedAt?.takeIf { it > 0 }?.let {
        SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(it))
    }

    var expanded by remember(ver) {
        mutableStateOf(selected == null || cachedModels.isEmpty())
    }
    var kind by remember(existing?.id, ver) { mutableStateOf(existing?.kind ?: ProviderKind.DeepSeek) }
    var base by remember(existing?.id, ver) {
        mutableStateOf(existing?.baseUrl?.takeIf { it.isNotBlank() } ?: ProviderKind.DeepSeek.baseUrl)
    }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgError by remember { mutableStateOf(false) }
    var models by remember(ver, existing?.id) { mutableStateOf(cachedModels) }
    var providerId by remember(ver, existing?.id) { mutableStateOf(existing?.id) }
    var picked by remember(ver, selected) {
        mutableStateOf(selected?.second ?: existing?.defaultModel)
    }

    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (onboardingStyle) 0.55f else 0.7f)
    val titleColor = MaterialTheme.colorScheme.onSurface

    fun saveAndFetch() {
        if (busy) return
        val id = providerId ?: existing?.id ?: MurfProviders.newId(context, kind)
        val apiKey = key.trim().ifBlank { MurfProviders.key(context, id).orEmpty() }
        if (apiKey.isBlank() && kind != ProviderKind.Custom) {
            msg = tr("Вставьте ключ API провайдера", "Paste the provider API key")
            msgError = true
            return
        }
        if (!base.trim().startsWith("http")) {
            msg = tr("Неверный адрес API", "Invalid API URL")
            msgError = true
            return
        }
        busy = true
        msg = null
        msgError = false
        scope.launch {
            val cfg = ProviderConfig(
                id = id,
                kind = kind,
                name = existing?.name ?: kind.title,
                baseUrl = base.trim().trimEnd('/'),
                models = models,
                defaultModel = picked,
                modelsFetchedAt = existing?.modelsFetchedAt ?: 0L,
            )
            MurfProviders.upsert(context, cfg, apiKey.takeIf { key.isNotBlank() })
            providerId = id
            val storedKey = MurfProviders.key(context, id).orEmpty()
            MurfProviders.fetchModels(cfg.kind, cfg.baseUrl, storedKey).fold(
                onSuccess = { list ->
                    val choose = picked?.takeIf { it in list } ?: list.firstOrNull()
                    val updated = cfg.copy(
                        models = list,
                        modelsFetchedAt = System.currentTimeMillis(),
                        defaultModel = choose,
                    )
                    MurfProviders.upsert(context, updated, null)
                    models = list
                    if (choose != null) {
                        MurfProviders.select(context, id, choose)
                        picked = choose
                    }
                    msg = tr("Моделей с API: ", "Models from API: ") + list.size
                    msgError = false
                    key = ""
                    ver++
                    if (list.isNotEmpty()) expanded = false
                },
                onFailure = { e ->
                    msg = ProviderApiErrors.userMessage(e)
                    msgError = true
                },
            )
            busy = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("Ключ провайдера и модель", "Provider key & model"),
            color = titleColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(
                "Для чат-агента на телефоне (Harness). Ключ шифруется на устройстве; модели — живой запрос к API провайдера.",
                "For the on-phone chat agent (Harness). Key stays encrypted on device; models come live from the provider API.",
            ),
            color = labelColor,
            style = MaterialTheme.typography.bodySmall,
        )

        if (!expanded && selected != null && models.isNotEmpty()) {
            Text(
                tr("Выбрано: ", "Selected: ") + "${selected.first} / ${selected.second}" +
                    (fetchedAtLabel?.let { tr(" · обновлено ", " · fetched ") + it } ?: ""),
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { expanded = true }) {
                Text(tr("Изменить / обновить модели", "Change / refresh models"))
            }
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ProviderKind.DeepSeek,
                ProviderKind.OpenAI,
                ProviderKind.XAI,
                ProviderKind.OpenRouter,
                ProviderKind.Anthropic,
                ProviderKind.Google,
            ).forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = {
                        kind = k
                        if (existing == null || existing.kind != k) base = k.baseUrl
                    },
                    label = { Text(k.title) },
                    enabled = !busy,
                )
            }
        }
        OutlinedTextField(
            value = base,
            onValueChange = { base = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Адрес API", "API URL")) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                val hasSaved = providerId?.let { MurfProviders.hasKey(context, it) } == true ||
                    existing?.let { MurfProviders.hasKey(context, it.id) } == true
                Text(
                    if (hasSaved) {
                        tr("Новый ключ (пусто — оставить сохранённый)", "New key (empty — keep saved)")
                    } else {
                        tr("Ключ API ", "API key ") + kind.keyHint
                    },
                )
            },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            onClick = { saveAndFetch() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (busy) tr("Загрузка моделей…", "Loading models…")
                else tr("Сохранить и загрузить модели", "Save & load models"),
            )
        }
        msg?.let {
            Text(
                it,
                color = if (msgError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (models.isNotEmpty()) {
            Text(
                tr("Выберите модель", "Pick a model"),
                color = titleColor,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                models.forEach { m ->
                    FilterChip(
                        selected = picked == m,
                        onClick = {
                            picked = m
                            val id = providerId ?: existing?.id
                            if (id != null) {
                                MurfProviders.select(context, id, m)
                                ver++
                            }
                        },
                        label = { Text(m) },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** Честные тексты ошибок API провайдера (без утечки ключа). */
object ProviderApiErrors {
    fun userMessage(error: Throwable): String {
        val raw = (error.message ?: error.javaClass.simpleName)
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-***")
            .replace(Regex("xai-[A-Za-z0-9_-]{8,}"), "xai-***")
            .replace(Regex("AIza[A-Za-z0-9_-]{8,}"), "AIza***")
        return when {
            raw.contains("Key rejected", ignoreCase = true) ||
                raw.contains("ключ не принят", ignoreCase = true) ||
                raw.contains("HTTP 401") || raw.contains("HTTP 403") ->
                tr(
                    "Ключ не принят провайдером (401/403). Проверьте ключ и права.",
                    "Provider rejected the key (401/403). Check the key and permissions.",
                )
            error is java.net.UnknownHostException ||
                error is java.net.SocketTimeoutException ||
                raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("failed to connect", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ||
                (error is java.io.IOException && (
                    raw.contains("connect", ignoreCase = true) ||
                        raw.contains("network", ignoreCase = true)
                    )) ->
                tr(
                    "Сеть недоступна или адрес API не отвечает. Проверьте интернет и URL.",
                    "Network unavailable or API URL not responding. Check internet and URL.",
                )
            raw.contains("Неверный адрес", ignoreCase = true) ||
                raw.contains("Invalid API", ignoreCase = true) ->
                tr("Неверный адрес API", "Invalid API URL")
            else -> tr("Не удалось получить модели: ", "Could not fetch models: ") + raw.take(180)
        }
    }
}
