package com.sbkubric.robby.ui.scenarios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.formatRunTime
import com.sbkubric.robby.ui.runStatusLabel
import com.sbkubric.robby.ui.summaryLine
import kotlinx.coroutines.launch

/**
 * Home: every scenario with its switch, summary and last run;
 * above them a red banner while the Telegram connector is broken, leading to «Активные апплеты».
 */
@Composable
fun ScenariosScreen(onOpen: (String) -> Unit, onOpenApplets: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val scenarios by remember { container.scenarios.observeAll() }.collectAsState(initial = null)
    val lastRuns by remember { container.activityLog.observeLastRuns() }.collectAsState(initial = emptyMap())
    val broken by container.telegram.brokenReason.collectAsState()

    Column(modifier.fillMaxSize()) {
        broken?.let { reason ->
            val name = container.catalog.applets.first { it.id == TelegramApplet.ID }.name
            BrokenBanner(stringResource(R.string.banner_broken, name, reason), onOpenApplets)
        }
        val list = scenarios ?: return@Column
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.scenarios_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        val switchLabel = stringResource(R.string.scenario_switch)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(list, key = { it.id }) { scenario ->
                val last = lastRuns[scenario.id]
                ListItem(
                    modifier = Modifier.clickable { onOpen(scenario.id) },
                    headlineContent = { Text(scenario.name) },
                    supportingContent = {
                        Text(
                            summaryLine(container.catalog.describe(scenario)) + "\n" + if (last == null) {
                                stringResource(R.string.scenario_never_ran)
                            } else {
                                stringResource(R.string.scenario_last_run, formatRunTime(last.time), runStatusLabel(last))
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = scenario.enabled,
                            onCheckedChange = { on -> container.appScope.launch { container.scenarios.save(scenario.copy(enabled = on)) } },
                            modifier = Modifier.semantics { contentDescription = switchLabel },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

/** «Telegram сломан: … Переподключить ›». */
@Composable
private fun BrokenBanner(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.banner_reconnect), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}
