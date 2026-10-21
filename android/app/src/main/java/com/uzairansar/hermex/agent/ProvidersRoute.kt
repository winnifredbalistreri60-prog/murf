package com.uzairansar.hermex.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки → Провайдеры моделей: ключи (зашифрованы), живые списки моделей, выбор модели для агентов на телефоне. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProvidersRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ver by remember { mutableIntStateOf(0) }
    val providers = remember(ver) { MurfProviders.list(context) }
    val sel = remember(ver) { MurfProviders.selected(context) }
    var edit by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun refresh(p: ProviderConfig) {
        val key = MurfProviders.key(context, p.id).orEmpty()
        busy = p.id; msg = null
        scope.launch {
            MurfProviders.fetchModels(p.kind, p.baseUrl, key).fold({ models ->
                MurfProviders.upsert(context, p.copy(models = models, modelsFetchedAt = System.currentTimeMillis(),
                    defaultModel = p.defaultModel?.takeIf { it in models } ?: models.firstOrNull()), null)
                if (MurfProviders.selected(context) == null) models.firstOrNull()?.let { MurfProviders.select(context, p.id, it) }
                msg = "${p.name}: " + tr("моделей ", "models ") + models.size
            }, { msg = "${p.name}: " + (it.message ?: "error") })
            busy = null; ver++
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Провайдеры моделей", "Model providers")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Ключи хранятся на телефоне в зашифрованном виде (Android Keystore) и передаются только провайдеру и агенту в локальной среде. Список моделей берётся с API провайдера.",
                "Keys are stored encrypted on the phone (Android Keystore) and sent only to the provider and the local agent. Model lists come live from the provider API."),
                style = MaterialTheme.typography.bodySmall)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            providers.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${p.name} · ${p.kind.title}", fontWeight = FontWeight.SemiBold)
                        Text((if (MurfProviders.hasKey(context, p.id)) tr("ключ сохранён", "key saved") else tr("без ключа", "no key")) +
                            " · " + p.baseUrl + " · " + tr("моделей: ", "models: ") + p.models.size +
                            (if (p.modelsFetchedAt > 0) " (" + SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(p.modelsFetchedAt)) + ")" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.layout.FlowRow(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.models.forEach { m ->
                                FilterChip(selected = sel == (p.id to m), onClick = { MurfProviders.select(context, p.id, m); ver++ }, label = { Text(m) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { refresh(p) }, enabled = busy == null) { Text(if (busy == p.id) "…" else tr("Обновить модели", "Refresh models")) }
                            TextButton(onClick = { edit = p }) { Text(tr("Изменить", "Edit")) }
                            TextButton(onClick = { MurfProviders.remove(context, p.id); ver++ }) { Text(tr("Удалить", "Delete")) }
                        }
                    }
                }
            }
            Button(onClick = { adding = true }) { Text(tr("Добавить провайдера", "Add provider")) }
            Text(tr("Цены для оценки стоимости — ориентировочная таблица MURF, не счёт провайдера.", "Cost estimates use MURF's approximate price table, not the provider's bill."),
                style = MaterialTheme.typography.labelSmall)
        }
    }

    if (adding || edit != null) ProviderDialog(edit, onDismiss = { adding = false; edit = null }) { cfg, key ->
        MurfProviders.upsert(context, cfg, key); adding = false; edit = null; ver++
        refresh(cfg)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderDialog(existing: ProviderConfig?, onDismiss: () -> Unit, onSave: (ProviderConfig, String?) -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(existing?.kind ?: ProviderKind.DeepSeek) }
    var name by remember { mutableStateOf(existing?.name ?: kind.title) }
    var base by remember { mutableStateOf(existing?.baseUrl ?: kind.baseUrl) }
    var key by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) tr("Новый провайдер", "New provider") else existing.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k; name = k.title; base = k.baseUrl }, label = { Text(k.title) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(tr("Название", "Name")) }, singleLine = true)
                OutlinedTextField(base, { base = it.trim() }, label = { Text(tr("Адрес API", "API URL")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it.trim() }, label = { Text(if (existing != null) tr("Новый ключ (пусто — оставить)", "New key (empty — keep)") else tr("Ключ API ", "API key ") + kind.keyHint) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            TextButton(enabled = base.startsWith("http") && (key.isNotBlank() || existing != null || kind == ProviderKind.Custom), onClick = {
                val cfg = (existing ?: ProviderConfig(MurfProviders.newId(context, kind), kind, name, base)).copy(name = name.ifBlank { kind.title }, baseUrl = base)
                onSave(cfg, key.ifBlank { null })
            }) { Text(tr("Сохранить и загрузить модели", "Save & load models")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } })
}
