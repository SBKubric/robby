package com.sbkubric.robby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.connectorStateLabel
import com.sbkubric.robby.ui.formatRunTime

/**
 * The Settings tab: «Активные апплеты», «Журнал активности» and the server phone's name;
 * later slices add export and phone checks.
 */
@Composable
fun SettingsScreen(onOpenApplets: () -> Unit, onOpenLog: () -> Unit, modifier: Modifier = Modifier) {
    val container = appContainer()
    val telegram by container.telegram.state.collectAsState(initial = ConnectorState.NOT_CONFIGURED)
    val runs by remember { container.activityLog.observeRecent() }.collectAsState(initial = emptyList())
    val phoneName = container.serverPhoneName
    val customName by phoneName.customName.collectAsState()
    var editName by rememberSaveable { mutableStateOf(false) }

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
        HorizontalDivider()
        NavRow(
            title = stringResource(R.string.settings_phone_name),
            summary = customName ?: stringResource(R.string.settings_phone_name_default, phoneName.defaultName()),
            onClick = { editName = true },
        )
    }

    if (editName) {
        PhoneNameDialog(
            initial = customName.orEmpty(),
            defaultName = phoneName.defaultName(),
            onSave = {
                phoneName.set(it)
                editName = false
            },
            onDismiss = { editName = false },
        )
    }
}

@Composable
private fun PhoneNameDialog(initial: String, defaultName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_phone_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phone_name_hint, defaultName), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.phone_name_field)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
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
