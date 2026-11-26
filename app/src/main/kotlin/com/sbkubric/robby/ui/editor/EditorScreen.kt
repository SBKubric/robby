package com.sbkubric.robby.ui.editor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbkubric.robby.R
import com.sbkubric.robby.applets.AppletPermissions
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.template.CommonVariables
import com.sbkubric.robby.ui.BackTopBar
import com.sbkubric.robby.ui.appContainer

/** One form «Когда / То»; conditions («Если») and Pause come in slice 3. */
@Composable
fun EditorScreen(scenarioId: String?, onBack: () -> Unit) {
    val container = appContainer()
    val catalog = container.catalog
    val vm = viewModel { EditorViewModel(container.scenarios, container.saveScenario, scenarioId) }
    val draft = vm.draft
    val context = LocalContext.current

    var picker by rememberSaveable { mutableStateOf<PickerKind?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Permissions the trigger needs, re-checked after the system dialog and on return from the app's settings.
    var permissionCheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permissionCheck++
        onPauseOrDispose {}
    }
    val missingPermissions = remember(draft.trigger?.id, permissionCheck) {
        draft.trigger?.id?.let { AppletPermissions.missing(context, it) }.orEmpty()
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionCheck++
    }

    val scroll = rememberScrollState()
    LaunchedEffect(vm.problems) { if (vm.problems.isNotEmpty()) scroll.animateScrollTo(0) }

    Scaffold(
        topBar = {
            BackTopBar(
                title = stringResource(if (vm.isNew) R.string.editor_title_new else R.string.editor_title_edit),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { vm.save(onBack) }, enabled = vm.loaded) { Text(stringResource(R.string.editor_save)) }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.problems.isNotEmpty()) Problems(vm.problems, catalog, draft.trigger?.id)

            OutlinedTextField(
                value = draft.name,
                onValueChange = vm::setName,
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.editor_when), stringResource(R.string.editor_when_hint))
            val trigger = draft.trigger
            if (trigger == null) {
                AddButton(stringResource(R.string.editor_pick_trigger)) { picker = PickerKind.TRIGGER }
            } else {
                val spec = catalog.trigger(trigger.id)
                ElementCard(
                    appletName = catalog.appletOf(trigger.id)?.name,
                    label = spec?.label ?: stringResource(R.string.editor_unknown_element, trigger.id),
                    buttonText = stringResource(R.string.editor_replace),
                    onButton = { picker = PickerKind.TRIGGER },
                ) {
                    spec?.params.orEmpty().forEach { param ->
                        ParamField(param, trigger.params[param.key].orEmpty(), emptyList()) { vm.setTriggerParam(param.key, it) }
                    }
                    if (missingPermissions.isNotEmpty()) {
                        PermissionHint(
                            onRequest = { requestPermissions.launch(missingPermissions.toTypedArray()) },
                            onOpenSettings = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            },
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.editor_then), stringResource(R.string.editor_then_hint))
            val variables = draft.trigger?.let { catalog.trigger(it.id) }?.variables.orEmpty() + CommonVariables.ALL
            draft.actions.forEachIndexed { index, action ->
                val spec = catalog.action(action.id)
                ElementCard(
                    appletName = catalog.appletOf(action.id)?.name,
                    label = spec?.label ?: stringResource(R.string.editor_unknown_element, action.id),
                    buttonText = stringResource(R.string.editor_remove),
                    onButton = { vm.removeAction(index) },
                ) {
                    spec?.params.orEmpty().forEach { param ->
                        ParamField(param, action.params[param.key].orEmpty(), variables) { vm.setActionParam(index, param.key, it) }
                    }
                }
            }
            AddButton(stringResource(R.string.editor_add_action)) { picker = PickerKind.ACTION }

            if (!vm.isNew) {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.editor_delete)) }
            }
        }
    }

    picker?.let { kind ->
        CatalogPicker(
            kind = kind,
            catalog = catalog,
            onTrigger = { spec ->
                vm.pickTrigger(spec)
                picker = null
                val missing = AppletPermissions.missing(context, spec.id)
                if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
            },
            onAction = { spec ->
                vm.addAction(spec)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text(stringResource(R.string.editor_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.editor_cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun ElementCard(
    appletName: String?,
    label: String,
    buttonText: String,
    onButton: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (appletName != null) {
                        Text(appletName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = onButton) { Text(buttonText) }
            }
            content()
        }
    }
}

@Composable
private fun ParamField(param: ParamSpec, value: String, variables: List<String>, onChange: (String) -> Unit) {
    if (param.isTemplate) {
        TemplateField(param.label, value, variables, onChange)
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(param.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionHint(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permission_hint), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.permission_hint_restricted), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRequest) { Text(stringResource(R.string.permission_request)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
            }
        }
    }
}

@Composable
private fun Problems(problems: List<ScenarioProblem>, catalog: AppletCatalog, triggerId: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            problems.forEach { problem ->
                val text = when (problem) {
                    ScenarioProblem.NoName -> stringResource(R.string.problem_no_name)
                    ScenarioProblem.NoTrigger -> stringResource(R.string.problem_no_trigger)
                    ScenarioProblem.NoActions -> stringResource(R.string.problem_no_actions)
                    ScenarioProblem.EmptyText -> stringResource(R.string.problem_empty_text)
                    is ScenarioProblem.UnknownElements ->
                        stringResource(R.string.problem_unknown, problem.ids.joinToString(", "))
                    is ScenarioProblem.UnavailableVariables -> stringResource(
                        R.string.problem_variables,
                        problem.variables.joinToString(", ") { "{$it}" },
                        triggerId?.let { catalog.trigger(it)?.label }.orEmpty(),
                    )
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
