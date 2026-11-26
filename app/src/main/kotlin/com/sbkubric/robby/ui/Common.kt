package com.sbkubric.robby.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.AppContainer
import com.sbkubric.robby.R
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.log.RunStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer

private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")
private val dateTime = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** «14:02» for today, «25.11 18:30» otherwise. */
fun formatRunTime(time: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = time.atZone(zone)
    return if (local.toLocalDate() == LocalDate.now(zone)) timeOnly.format(local) else dateTime.format(local)
}

@Composable
fun runStatusLabel(status: RunStatus): String = stringResource(
    when (status) {
        RunStatus.DONE -> R.string.run_done
        RunStatus.FAILED -> R.string.run_failed
    },
)

@Composable
fun connectorStateLabel(state: ConnectorState): String = stringResource(
    when (state) {
        ConnectorState.NOT_CONFIGURED -> R.string.connector_not_configured
        ConnectorState.WORKING -> R.string.connector_working
        ConnectorState.BROKEN -> R.string.connector_broken
    },
)

/** A small coloured label: run status or connector state. */
@Composable
fun StatusBadge(text: String, good: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (good) colors.secondaryContainer else colors.errorContainer,
        contentColor = if (good) colors.onSecondaryContainer else colors.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row {
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = { actions() },
    )
}
