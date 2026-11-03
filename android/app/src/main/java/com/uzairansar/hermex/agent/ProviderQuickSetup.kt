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
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Авторизация для Harness на телефоне — аналог method «api-key» / «Paste a key»
 * из @deepseek-ai/dsh-authorization: человек вставляет токен, MURF проверяет его
 * (живой список моделей) и сохраняет в EncryptedSharedPreferences.
 * OAuth/device-code потоки оригинального DSH (ChatGPT и др.) пока не встроены —
 * DeepSeek и большинство провайдеров работают через API-токен.
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
        val id = if (existing != null && existing.kind != kind) MurfProviders.newId(context, kind)
            else providerId ?: existing?.id ?: MurfProviders.newId(context, kind)
        val apiKey = key.trim().ifBlank { MurfProviders.key(context, id).orEmpty() }
        if (apiKey.isBlank() && kind != ProviderKind.Custom) {
            msg = localizedString("Paste the provider API key")
            msgError = true
            return
        }
        if (!base.trim().startsWith("http")) {
            msg = localizedString("Invalid API URL")
            msgError = true
            return
        }
        busy = true
        msg = null
        msgError = false
        scope.launch {
            try {
                // If user pasted sk-… while Anthropic chip was leftover, switch to DeepSeek/OpenAI — not Anthropic.
                val prevKind = kind
                val inferred = if (key.isNotBlank()) MurfProviders.inferKindFromKey(apiKey, prevKind) else prevKind
                val switched = inferred != prevKind
                if (switched) {
                    kind = inferred
                    base = inferred.baseUrl
                }
                val useKind = inferred
                val useBase = (if (switched) inferred.baseUrl else base).trim().trimEnd('/').ifBlank { useKind.baseUrl }
                // New kind → new id (do not keep "anthropic" id when user pasted a DeepSeek key).
                val useId = when {
                    existing != null && existing.kind == useKind -> existing.id
                    !switched && existing == null && id.isNotBlank() -> id
                    else -> MurfProviders.newId(context, useKind)
                }
                // A typed key belongs to the provider it was typed for; never reuse another provider's stored key.
                val useKey = if (key.isNotBlank()) apiKey else MurfProviders.key(context, useId).orEmpty()
                val cfg = ProviderConfig(
                    id = useId,
                    kind = useKind,
                    name = existing?.name?.takeIf { existing.kind == useKind } ?: useKind.title,
                    baseUrl = useBase,
                    models = if (existing?.kind == useKind) models else emptyList(),
                    defaultModel = picked?.takeIf { existing?.kind == useKind },
                    modelsFetchedAt = existing?.modelsFetchedAt?.takeIf { existing.kind == useKind } ?: 0L,
                )
                // Drop stale Anthropic (or any other) selection until this key's models are confirmed.
                val prevSel = MurfProviders.selected(context)
                if (switched || prevSel?.first != useId) {
                    MurfProviders.clearSelection(context)
                    picked = null
                }
                MurfProviders.fetchModels(cfg.kind, cfg.baseUrl, useKey).fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            msg = localizedString("Provider returned no models")
                            msgError = true
                            return@fold
                        }
                        val choose = picked?.takeIf { it in list && existing?.kind == useKind } ?: list.first()
                        val updated = cfg.copy(
                            models = list,
                            modelsFetchedAt = System.currentTimeMillis(),
                            defaultModel = choose,
                        )
                        // Key is persisted only after the provider accepted it.
                        MurfProviders.upsert(context, updated, useKey.takeIf { key.isNotBlank() })
                        providerId = useId
                        models = list
                        MurfProviders.select(context, useId, choose)
                        picked = choose
                        msg = localizedString("Models from API: ") + list.size
                        msgError = false
                        key = ""
                        ver++
                        expanded = false
                    },
                    onFailure = { e ->
                        msg = ProviderApiErrors.userMessage(e)
                        msgError = true
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = ProviderApiErrors.userMessage(e)
                msgError = true
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            localizedString("Authorization (API token)"),
            color = titleColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(
                "Как «Paste a key» в DeepSeek Harness: вставьте API-токен провайдера. Он шифруется на телефоне и уходит только в локальный dsh / API провайдера. Модели подтягиваются живым запросом.",
                "Like “Paste a key” in DeepSeek Harness: paste the provider API token. It stays encrypted on the phone and only goes to local dsh / the provider API. Models are fetched live.",
            ),
            color = labelColor,
            style = MaterialTheme.typography.bodySmall,
        )

        if (!expanded && selected != null && models.isNotEmpty()) {
            val authed = MurfProviders.isAuthorized(context)
            Text(
                (if (authed) "✓ " else "⚠ ") +
                    localizedString("Selected: ") + "${selected.first} / ${selected.second}" +
                    (fetchedAtLabel?.let { localizedString(" · fetched ") + it } ?: "") +
                    if (authed) localizedString(" · authorized") else localizedString(" · no token"),
                color = if (authed) titleColor else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { expanded = true }) {
                Text(
                    if (authed) localizedString("Change token / models")
                    else localizedString("Paste token & load models"),
                )
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
                        if (kind != k) {
                            models = emptyList()
                            picked = null
                            key = ""
                        }
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
            label = { Text(localizedString("API URL")) },
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
                        localizedString("New token (empty — keep saved)")
                    } else {
                        localizedString("API token / key ") + kind.keyHint
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
                if (busy) localizedString("Verifying token…")
                else localizedString("Authorize & load models"),
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
                localizedString("Pick a model"),
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
                localizedString("Invalid API URL")
            else -> localizedString("Could not fetch models. Check the settings and try again.")
        }
    }
}
