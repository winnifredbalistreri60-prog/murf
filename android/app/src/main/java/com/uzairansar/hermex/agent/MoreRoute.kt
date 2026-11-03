package com.uzairansar.hermex.agent

import com.uzairansar.hermex.ui.localization.localizedString
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import com.uzairansar.hermex.data.preferences.modelIdentifier
import com.uzairansar.hermex.data.preferences.normalizedProvider
import com.uzairansar.hermex.data.preferences.displayModelTitle
import com.uzairansar.hermex.data.preferences.favoriteKeyOrNull
import com.uzairansar.hermex.data.preferences.visibleFavoriteModels
import com.uzairansar.hermex.core.model.isConfirmedDefaultModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uzairansar.hermex.R

/** Navigation catalog for daily shortcuts and grouped utilities. */
object MurfMoreNav {
    data class Item(
        val routeOrPanel: String,
        @param:StringRes val label: Int,
        @param:DrawableRes val icon: Int,
        val kind: Kind,
    )

    enum class Kind { Route, Panel }

    data class Group(val titleRu: String, val titleEn: String, val items: List<Item>)

    fun groups(): List<Group> = listOf(
        Group("Интеграции", "Integrations", listOf(
            Item("devices", R.string.nav_devices, R.drawable.ic_lucide_smartphone, Kind.Route),
            Item("drive", R.string.nav_drive, R.drawable.ic_lucide_cloud, Kind.Route),
            Item("phone", R.string.nav_phone, R.drawable.ic_lucide_smartphone, Kind.Route),
        )),
        Group("Работа", "Work", listOf(
            Item("tasks", R.string.nav_tasks, R.drawable.ic_lucide_calendar_clock, Kind.Panel),
            Item("kanban", R.string.nav_kanban, R.drawable.ic_lucide_columns_3, Kind.Route),
            Item("bottasks", R.string.nav_bot_tasks, R.drawable.ic_hermex_lightning_bolt, Kind.Route),
        )),
        Group("Агент", "Agent", listOf(
            Item("dsh", R.string.nav_dsh, R.drawable.ic_hermex_sparkles, Kind.Route),
            Item("memory", R.string.nav_memory, R.drawable.ic_lucide_brain, Kind.Panel),
            Item("insights", R.string.nav_insights, R.drawable.ic_lucide_chart_column_increasing, Kind.Panel),
        )),
        Group("Расширенные", "Advanced", listOf(
            Item("providers", R.string.nav_providers, R.drawable.ic_lucide_cloud, Kind.Route),
            Item("clis", R.string.nav_clis, R.drawable.ic_hermex_tools_wrench, Kind.Route),
            Item("local", R.string.nav_local, R.drawable.ic_lucide_server, Kind.Route),
            Item("deploy", R.string.nav_deploy, R.drawable.ic_lucide_cloud, Kind.Route),
        )),
    )

    fun items(): List<Item> = groups().flatMap { it.items } +
        Item("skills", R.string.nav_skills, R.drawable.ic_lucide_hammer, Kind.Panel)

    fun primaryIds(): List<String> = listOf("screen", "bots", "tasks", "kanban", "more")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreRoute(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    panelsRepository: com.uzairansar.hermex.data.repository.PanelsRepository?,
    localSettingsRepository: com.uzairansar.hermex.data.preferences.LocalSettingsRepository,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var catalog by androidx.compose.runtime.remember(panelsRepository) { androidx.compose.runtime.mutableStateOf<com.uzairansar.hermex.core.model.ModelCatalogResponse?>(null) }
    var currentModel by androidx.compose.runtime.remember(panelsRepository) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var currentProvider by androidx.compose.runtime.remember(panelsRepository) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var skillCount by androidx.compose.runtime.remember(panelsRepository) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var saving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favorites by localSettingsRepository.favoriteModelKeys.collectAsState(initial = emptyList())
    androidx.compose.runtime.LaunchedEffect(panelsRepository) {
        if (panelsRepository != null) {
            try { skillCount = panelsRepository.skills().size } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            try {
                val models = panelsRepository.models()
                catalog = models
                currentModel = models.defaultModel
                currentProvider = models.activeProvider
                try {
                    val settings = panelsRepository.settings()
                    currentModel = settings.defaultModel ?: currentModel
                    currentProvider = settings.defaultModelProvider ?: currentProvider
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true }
        }
    }
    var reasoning by androidx.compose.runtime.remember(panelsRepository, currentModel, currentProvider) { androidx.compose.runtime.mutableStateOf<com.uzairansar.hermex.core.model.ReasoningResponse?>(null) }
    androidx.compose.runtime.LaunchedEffect(panelsRepository, currentModel, currentProvider, sheet) {
        if (sheet && panelsRepository != null && currentModel != null) {
            try { reasoning = panelsRepository.reasoning(currentModel, currentProvider) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { reasoning = null }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.nav_more)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), localizedString("Back")) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column {
                    MoreRow(localizedString("Model"), listOfNotNull(currentModel, currentProvider).joinToString(" · ").let { if (it.isBlank()) localizedString("Unknown") else it }, R.drawable.ic_hermex_sparkles) { sheet = true }
                    MoreRow(localizedString("Skills"), skillCount?.toString() ?: "—", R.drawable.ic_lucide_hammer) { onOpenPanel("skills") }
                }
            }
            MurfMoreNav.groups().forEachIndexed { index, group ->
                if (index < 3) Text(localizedString(group.titleEn), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column {
                        if (index == 3) MoreRow(tr(group.titleRu, group.titleEn), if (expanded) "−" else "+", R.drawable.ic_hermex_tools_wrench) { expanded = !expanded }
                        if (index != 3 || expanded) group.items.forEach { item ->
                            MoreRow(stringResource(item.label), null, item.icon) {
                                when (item.kind) {
                                    MurfMoreNav.Kind.Route -> onOpenRoute(item.routeOrPanel)
                                    MurfMoreNav.Kind.Panel -> onOpenPanel(item.routeOrPanel)
                                }
                            }
                        }
                    }
                }
            }
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                MoreRow(localizedString("Settings"), null, R.drawable.ic_hermex_tools_wrench) { onOpenRoute("settings") }
            }
        }
    }
    if (sheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(localizedString("Model settings"), style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(currentModel, currentProvider).joinToString(" · ").let { if (it.isBlank()) localizedString("No model selected") else it }, modifier = Modifier.padding(vertical = 12.dp))
                val reasoningDescription = localizedString("Reasoning effort")
                val efforts = reasoning?.supportedEfforts.orEmpty().distinct()
                val effortIndex = efforts.indexOf(reasoning?.effectiveEffort)
                if (reasoning?.supportsReasoningEffort == true && efforts.size > 1 && effortIndex >= 0) {
                    Text(localizedString("Reasoning effort") + ": " + efforts[effortIndex])
                    androidx.compose.material3.Slider(
                        value = effortIndex.toFloat(),
                        onValueChange = { index ->
                            reasoning = reasoning?.copy(reasoningEffort = efforts[index.toInt().coerceIn(efforts.indices)])
                        },
                        onValueChangeFinished = {
                            val selected = reasoning?.effectiveEffort
                            if (selected != null) scope.launch {
                                try { reasoning = panelsRepository?.setReasoning(selected, currentModel, currentProvider) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                                    error = true
                                    try { reasoning = panelsRepository?.reasoning(currentModel, currentProvider) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { reasoning = null }
                                }
                            }
                        },
                        valueRange = 0f..(efforts.size - 1).toFloat(), steps = efforts.size - 2,
                        modifier = Modifier.semantics { contentDescription = reasoningDescription },
                    )
                }
                if (error) Text(localizedString("Could not load models"), color = MaterialTheme.colorScheme.error)
                val models = catalog?.flattenedModels.orEmpty().filter { it.modelIdentifier != null }
                val favoriteModels = models.visibleFavoriteModels(favorites)
                if (favoriteModels.isNotEmpty()) {
                    Text(localizedString("Favorites"), style = MaterialTheme.typography.titleSmall)
                    favoriteModels.forEach { model ->
                        ModelSheetRow(model, true, saving, onFavorite = { scope.launch { localSettingsRepository.toggleFavoriteModel(model) } }) {
                            scope.launch {
                                saving = true
                                try {
                                    val response = panelsRepository?.saveDefaultModel(model.modelIdentifier!!, model.normalizedProvider)
                                    if (response != null && response.isConfirmedDefaultModel(model.normalizedProvider)) {
                                        currentModel = response.model ?: model.modelIdentifier
                                        currentProvider = response.provider ?: model.normalizedProvider
                                        error = false
                                    } else error = true
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true } finally { saving = false }
                            }
                        }
                    }
                }
                if (models.isNotEmpty()) Text(localizedString("All models"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                models.forEach { model ->
                    ModelSheetRow(model, model.favoriteKeyOrNull() in favorites, saving, onFavorite = { scope.launch { localSettingsRepository.toggleFavoriteModel(model) } }) {
                        scope.launch {
                            saving = true
                            try {
                                val response = panelsRepository?.saveDefaultModel(model.modelIdentifier!!, model.normalizedProvider)
                                if (response != null && response.isConfirmedDefaultModel(model.normalizedProvider)) {
                                    currentModel = response.model ?: model.modelIdentifier
                                    currentProvider = response.provider ?: model.normalizedProvider
                                    error = false
                                } else error = true
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { error = true } finally { saving = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreRow(label: String, value: String?, @DrawableRes icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelSheetRow(model: com.uzairansar.hermex.core.model.ModelSummary, favorite: Boolean, saving: Boolean, onFavorite: () -> Unit, onSelect: () -> Unit) {
    val favoriteDescription = if (favorite) localizedString("Remove favorite") else localizedString("Add favorite")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = !saving, onClick = onSelect).padding(vertical = 8.dp)) {
            Text(model.displayModelTitle)
            model.normalizedProvider?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        IconButton(onClick = onFavorite) {
            Text(if (favorite) "★" else "☆", modifier = Modifier.semantics { contentDescription = favoriteDescription })
        }
    }
}
