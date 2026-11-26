package com.sbkubric.robby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.ui.BackTopBar
import com.sbkubric.robby.ui.BadgeTone
import com.sbkubric.robby.ui.StatusBadge
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.connectorStateLabel

/** Only the applets that need setup: in this slice, the Telegram connector. */
@Composable
fun ActiveAppletsScreen(onBack: () -> Unit, onSetupTelegram: () -> Unit) {
    val container = appContainer()
    val state by container.telegram.state.collectAsState(initial = ConnectorState.NOT_CONFIGURED)
    val settings by container.telegram.settings.collectAsState()
    val broken by container.telegram.brokenReason.collectAsState()

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_applets), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.applets_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            container.catalog.applets.filter { it.isConnector }.forEach { applet ->
                when (applet.id) {
                    TelegramApplet.ID -> ConnectorCard(
                        applet = applet,
                        state = state,
                        details = settings?.let { stringResource(R.string.applets_telegram_details, it.botUsername, it.groupTitle) },
                        problem = broken?.takeIf { state == ConnectorState.BROKEN }?.let { stringResource(R.string.applets_broken_reason, it) },
                        onSetup = onSetupTelegram,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectorCard(applet: Applet, state: ConnectorState, details: String?, problem: String?, onSetup: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(applet.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.applets_connector), style = MaterialTheme.typography.labelMedium)
                StatusBadge(connectorStateLabel(state), if (state == ConnectorState.WORKING) BadgeTone.GOOD else BadgeTone.BAD)
            }
            Text(
                stringResource(R.string.applets_actions, applet.actions.joinToString(", ") { it.label }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (details != null) Text(details, style = MaterialTheme.typography.bodyMedium)
            if (problem != null) Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = onSetup) {
                Text(stringResource(if (state == ConnectorState.NOT_CONFIGURED) R.string.applets_setup else R.string.applets_setup_again))
            }
        }
    }
}
