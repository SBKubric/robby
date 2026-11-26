package com.sbkubric.robby.ui.scenarios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.formatRunTime
import com.sbkubric.robby.ui.runStatusLabel
import kotlinx.coroutines.launch

/** Home: every scenario with its switch, summary and last run. */
@Composable
fun ScenariosScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val scenarios by remember { container.scenarios.observeAll() }.collectAsState(initial = null)
    val lastRuns by remember { container.activityLog.observeLastRuns() }.collectAsState(initial = emptyMap())

    val list = scenarios ?: return
    if (list.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.scenarios_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val switchLabel = stringResource(R.string.scenario_switch)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(list, key = { it.id }) { scenario ->
            val last = lastRuns[scenario.id]
            ListItem(
                modifier = Modifier.clickable { onOpen(scenario.id) },
                headlineContent = { Text(scenario.name) },
                supportingContent = {
                    Text(
                        container.catalog.summary(scenario) + "\n" + if (last == null) {
                            stringResource(R.string.scenario_never_ran)
                        } else {
                            stringResource(R.string.scenario_last_run, formatRunTime(last.time), runStatusLabel(last.status))
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
