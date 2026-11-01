package com.uzairansar.hermex.agent

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
        Group("Агенты и модели", "Agents and models", listOf(
            Item("dsh", R.string.nav_dsh, R.drawable.ic_hermex_sparkles, Kind.Route),
            Item("providers", R.string.nav_providers, R.drawable.ic_lucide_cloud, Kind.Route),
            Item("clis", R.string.nav_clis, R.drawable.ic_hermex_tools_wrench, Kind.Route),
        )),
        Group("Задачи и знания", "Tasks and knowledge", listOf(
            Item("tasks", R.string.nav_tasks, R.drawable.ic_lucide_calendar_clock, Kind.Panel),
            Item("kanban", R.string.nav_kanban, R.drawable.ic_lucide_columns_3, Kind.Route),
            Item("bottasks", R.string.nav_bot_tasks, R.drawable.ic_hermex_lightning_bolt, Kind.Route),
            Item("skills", R.string.nav_skills, R.drawable.ic_lucide_hammer, Kind.Panel),
            Item("memory", R.string.nav_memory, R.drawable.ic_lucide_brain, Kind.Panel),
            Item("insights", R.string.nav_insights, R.drawable.ic_lucide_chart_column_increasing, Kind.Panel),
        )),
        Group("Подключения и устройства", "Connections and devices", listOf(
            Item("devices", R.string.nav_devices, R.drawable.ic_lucide_smartphone, Kind.Route),
            Item("drive", R.string.nav_drive, R.drawable.ic_lucide_cloud, Kind.Route),
            Item("phone", R.string.nav_phone, R.drawable.ic_lucide_smartphone, Kind.Route),
        )),
        Group("Установка и обслуживание", "Setup and maintenance", listOf(
            Item("local", R.string.nav_local, R.drawable.ic_lucide_server, Kind.Route),
            Item("deploy", R.string.nav_deploy, R.drawable.ic_lucide_cloud, Kind.Route),
        )),
    )

    /** All utilities remain reachable even when home shortcuts are hidden. */
    fun items(): List<Item> = groups().flatMap { it.items }

    fun primaryIds(): List<String> = listOf("screen", "bots", "tasks", "kanban", "more")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreRoute(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_more)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back"))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MurfMoreNav.groups().forEach { group ->
                Text(
                    tr(group.titleRu, group.titleEn),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
                group.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                when (item.kind) {
                                    MurfMoreNav.Kind.Route -> onOpenRoute(item.routeOrPanel)
                                    MurfMoreNav.Kind.Panel -> onOpenPanel(item.routeOrPanel)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            painterResource(item.icon),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(item.label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
