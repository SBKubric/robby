package com.sbkubric.robby.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.ActionSpec
import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.ConditionSpec
import com.sbkubric.robby.core.applet.TriggerSpec

enum class PickerKind { TRIGGER, CONDITION, ACTION }

/**
 * «Сначала апплет»: tiles of the applets that offer this kind of element (trigger, condition or action),
 * then only that applet's elements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogPicker(
    kind: PickerKind,
    catalog: AppletCatalog,
    onTrigger: (TriggerSpec) -> Unit,
    onCondition: (ConditionSpec) -> Unit,
    onAction: (ActionSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var appletId by rememberSaveable { mutableStateOf<String?>(null) }
    val applets = when (kind) {
        PickerKind.TRIGGER -> catalog.appletsWithTriggers()
        PickerKind.CONDITION -> catalog.appletsWithConditions()
        PickerKind.ACTION -> catalog.appletsWithActions()
    }
    val applet = applets.firstOrNull { it.id == appletId }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (applet == null) {
                Text(
                    stringResource(
                        when (kind) {
                            PickerKind.TRIGGER -> R.string.picker_trigger_title
                            PickerKind.CONDITION -> R.string.picker_condition_title
                            PickerKind.ACTION -> R.string.picker_action_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                applets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { AppletTile(it, kind, Modifier.weight(1f)) { appletId = it.id } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { appletId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(applet.name, style = MaterialTheme.typography.titleLarge)
                }
                when (kind) {
                    PickerKind.TRIGGER -> applet.triggers.forEach { spec ->
                        ListItem(
                            modifier = Modifier.clickable { onTrigger(spec) },
                            headlineContent = { Text(spec.label) },
                            supportingContent = {
                                Text(
                                    if (spec.variables.isEmpty()) {
                                        stringResource(R.string.picker_no_variables)
                                    } else {
                                        spec.variableNames.joinToString(" ") { "{$it}" }
                                    },
                                )
                            },
                        )
                    }
                    PickerKind.CONDITION -> applet.conditions.forEach { spec ->
                        ListItem(modifier = Modifier.clickable { onCondition(spec) }, headlineContent = { Text(spec.label) })
                    }
                    PickerKind.ACTION -> applet.actions.forEach { spec ->
                        ListItem(modifier = Modifier.clickable { onAction(spec) }, headlineContent = { Text(spec.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppletTile(applet: Applet, kind: PickerKind, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier.heightIn(min = 88.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(applet.name, style = MaterialTheme.typography.titleMedium)
            Text(
                when (kind) {
                    PickerKind.TRIGGER -> stringResource(R.string.picker_triggers_count, applet.triggers.size)
                    PickerKind.CONDITION -> stringResource(R.string.picker_conditions_count, applet.conditions.size)
                    PickerKind.ACTION -> stringResource(R.string.picker_actions_count, applet.actions.size)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (applet.isConnector) {
                Text(
                    stringResource(R.string.picker_connector),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
