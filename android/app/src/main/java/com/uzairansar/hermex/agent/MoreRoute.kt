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

/**
 * Secondary utility destinations demoted from the primary sidebar (MURF 1.10.2).
 * Underlying routes stay registered; this is nav IA only.
 */
object MurfMoreNav {
    data class Item(
        val routeOrPanel: String,
        @param:StringRes val label: Int,
        @param:DrawableRes val icon: Int,
        val kind: Kind,
    )

    enum class Kind { Route, Panel }

    /** Stable list for unit tests and MoreRoute. */
    fun items(): List<Item> = listOf(
        Item("phone", R.string.nav_phone, R.drawable.ic_lucide_smartphone, Kind.Route),
        Item("providers", R.string.nav_providers, R.drawable.ic_lucide_cloud, Kind.Route),
        Item("bottasks", R.string.nav_bot_tasks, R.drawable.ic_hermex_lightning_bolt, Kind.Route),
        Item("clis", R.string.nav_clis, R.drawable.ic_hermex_tools_wrench, Kind.Route),
        Item("skills", R.string.nav_skills, R.drawable.ic_lucide_hammer, Kind.Panel),
        Item("memory", R.string.nav_memory, R.drawable.ic_lucide_brain, Kind.Panel),
        Item("insights", R.string.nav_insights, R.drawable.ic_lucide_chart_column_increasing, Kind.Panel),
    )

    /** Primary sidebar order shipped in 1.10.2 (ids only). */
    fun primaryIds(): List<String> = listOf(
        "screen", "dsh", "bots", "devices", "local", "drive", "deploy", "tasks", "kanban", "more",
    )
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
            MurfMoreNav.items().forEach { item ->
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
