package com.sbkubric.robby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.connectorStateLabel
import com.sbkubric.robby.ui.formatRunTime

/** The Settings tab: «Активные апплеты» and «Журнал активности»; later slices add export and phone checks. */
@Composable
fun SettingsScreen(onOpenApplets: () -> Unit, onOpenLog: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val telegram by container.telegram.state.collectAsState(initial = ConnectorState.NOT_CONFIGURED)
    val runs by remember { container.activityLog.observeRecent() }.collectAsState(initial = emptyList())

    Column(modifier.fillMaxSize()) {
        NavRow(
            title = stringResource(R.string.settings_applets),
            summary = stringResource(R.string.settings_applets_summary, connectorStateLabel(telegram)),
            onClick = onOpenApplets,
        )
        HorizontalDivider()
        val last = runs.firstOrNull()
        NavRow(
            title = stringResource(R.string.settings_log),
            summary = if (last == null) {
                stringResource(R.string.settings_log_empty)
            } else {
                stringResource(R.string.settings_log_summary, formatRunTime(last.time), last.scenarioName)
            },
            onClick = onOpenLog,
        )
    }
}

@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}
