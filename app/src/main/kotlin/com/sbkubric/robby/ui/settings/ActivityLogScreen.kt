package com.sbkubric.robby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.ui.BackTopBar
import com.sbkubric.robby.ui.StatusBadge
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.formatRunTime
import com.sbkubric.robby.ui.runMessage
import com.sbkubric.robby.ui.runStatusLabel

/** «Журнал активности»: every run, newest first. */
@Composable
fun ActivityLogScreen(onBack: () -> Unit) {
    val container = appContainer()
    val runs by remember { container.activityLog.observeRecent() }.collectAsState(initial = emptyList())

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_log), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(if (runs.isEmpty()) R.string.log_empty else R.string.log_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(runs) { run ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatRunTime(run.time), style = MaterialTheme.typography.labelLarge)
                        Text(run.scenarioName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusBadge(runStatusLabel(run.status), good = run.status == RunStatus.DONE)
                    }
                    runMessage(run)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
