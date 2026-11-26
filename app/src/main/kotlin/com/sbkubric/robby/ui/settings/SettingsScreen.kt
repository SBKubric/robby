package com.sbkubric.robby.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbkubric.robby.R
import com.sbkubric.robby.checks.ServerPhoneChecks
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.settings.ImportSettings
import com.sbkubric.robby.ui.appContainer
import com.sbkubric.robby.ui.connectorStateLabel
import com.sbkubric.robby.ui.formatRunTime
import com.sbkubric.robby.ui.scenarioProblemMark
import java.time.LocalDate

/**
 * The Settings tab: «Активные апплеты», «Журнал активности», the server phone's name, export and import,
 * and the collapsed «Проверки телефона-сервера» ([checks], read by the home screen for the tab's red dot).
 */
@Composable
fun SettingsScreen(
    checks: List<ServerPhoneChecks.Result>,
    onOpenApplets: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val context = LocalContext.current
    val telegram by container.telegram.state.collectAsState(initial = ConnectorState.NOT_CONFIGURED)
    val runs by remember { container.activityLog.observeRecent() }.collectAsState(initial = emptyList())
    val phoneName = container.serverPhoneName
    val customName by phoneName.customName.collectAsState()
    var editName by rememberSaveable { mutableStateOf(false) }
    var checksOpen by rememberSaveable { mutableStateOf(false) }

    val transfer = viewModel {
        SettingsTransferViewModel(
            context.applicationContext.contentResolver,
            container.exportSettings,
            container.importSettings,
            container.scenarios,
            container.appScope,
        )
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON)) { uri ->
        uri?.let(transfer::export)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(transfer::read) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavRow(
            title = stringResource(R.string.settings_applets),
            summary = stringResource(R.string.settings_applets_summary, connectorStateLabel(telegram)),
            onClick = onOpenApplets,
            problem = telegram == ConnectorState.BROKEN,
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
        HorizontalDivider()
        NavRow(
            title = stringResource(R.string.settings_export),
            summary = stringResource(R.string.settings_export_summary),
            onClick = { exportFile.launch("robby-${LocalDate.now()}.json") },
        )
        HorizontalDivider()
        NavRow(
            title = stringResource(R.string.settings_import),
            summary = stringResource(R.string.settings_import_summary),
            // Some file managers report a .json file as a generic binary: offer those too.
            onClick = { importFile.launch(arrayOf(JSON, "application/octet-stream")) },
        )
        HorizontalDivider()
        NavRow(
            title = stringResource(R.string.settings_checks),
            summary = stringResource(R.string.settings_checks_summary, checks.count { it.ok }, checks.size),
            onClick = { checksOpen = !checksOpen },
            problem = checks.any { !it.ok },
            icon = if (checksOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        )
        if (checksOpen) PhoneChecksList(checks, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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

    TransferDialog(transfer.state, onConfirm = transfer::confirm, onDismiss = transfer::dismiss, onOpenApplets = {
        transfer.dismiss()
        onOpenApplets()
    })
}

private const val JSON = "application/json"

@Composable
private fun TransferDialog(
    state: SettingsTransferViewModel.State,
    onConfirm: (ImportSettings.Plan) -> Unit,
    onDismiss: () -> Unit,
    onOpenApplets: () -> Unit,
) {
    val ok: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) } }
    when (state) {
        SettingsTransferViewModel.State.Idle -> Unit
        SettingsTransferViewModel.State.Exported -> Message(stringResource(R.string.export_done), ok, onDismiss)
        is SettingsTransferViewModel.State.ExportFailed -> Message(stringResource(R.string.export_failed, state.reason), ok, onDismiss)
        is SettingsTransferViewModel.State.ReadFailed -> Message(stringResource(R.string.import_read_failed, state.reason), ok, onDismiss)
        SettingsTransferViewModel.State.NewerVersion -> Message(stringResource(R.string.import_newer), ok, onDismiss)
        SettingsTransferViewModel.State.NotSettings -> Message(stringResource(R.string.import_not_settings), ok, onDismiss)
        is SettingsTransferViewModel.State.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.import_confirm_text,
                        state.current,
                        state.plan.scenarios.size,
                        state.plan.connectors.joinToString(", "),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { onConfirm(state.plan) }) { Text(stringResource(R.string.import_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
        )
        is SettingsTransferViewModel.State.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_done_title)) },
            text = { ImportSummary(state.plan) },
            confirmButton = { TextButton(onClick = onOpenApplets) { Text(stringResource(R.string.import_open_applets)) } },
            dismissButton = ok,
        )
    }
}

/** «Сценариев: N», the ones switched off and why, and the connectors to set up again. */
@Composable
private fun ImportSummary(plan: ImportSettings.Plan) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.import_done_count, plan.scenarios.size))
        if (plan.disabled.isNotEmpty()) {
            Text(stringResource(R.string.import_done_disabled, plan.disabled.size))
            plan.disabled.forEach { disabled ->
                Text(
                    "• ${disabled.name}: ${scenarioProblemMark(disabled.problems).orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (plan.connectors.isNotEmpty()) Text(stringResource(R.string.import_done_connectors, plan.connectors.joinToString(", ")))
    }
}

@Composable
private fun Message(text: String, confirm: @Composable () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, text = { Text(text) }, confirmButton = confirm)
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
private fun NavRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    problem: Boolean = false,
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(summary, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { Icon(icon, contentDescription = null) },
    )
}
