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
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.applet.ScenarioDescription
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunReason
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
        RunStatus.SKIPPED -> R.string.run_skipped
    },
)

fun runTone(status: RunStatus): BadgeTone = when (status) {
    RunStatus.DONE -> BadgeTone.GOOD
    RunStatus.FAILED -> BadgeTone.BAD
    RunStatus.SKIPPED -> BadgeTone.NEUTRAL
}

/** What the activity log says about a run: the applet's own text, or the engine's reason from strings.xml. */
@Composable
fun runMessage(run: Run, catalog: AppletCatalog): String? = when (run.reason) {
    RunReason.NO_PERFORMER -> stringResource(R.string.run_error_no_performer, run.message.orEmpty())
    RunReason.TIMED_OUT -> stringResource(R.string.run_error_timed_out)
    RunReason.CONDITION_NOT_MET -> stringResource(
        R.string.run_skipped_condition,
        run.message?.let { id -> catalog.condition(id)?.label ?: id }.orEmpty(),
    )
    RunReason.COOLDOWN -> stringResource(R.string.run_skipped_cooldown)
    null -> run.message
}

/** «По расписанию (по будням, 09:00) · если Окно времени (09:00, 18:00) → Telegram: Отправить сообщение в группу · пауза 1 ч». */
@Composable
fun summaryLine(description: ScenarioDescription): String {
    fun withValues(label: String, values: List<String>) = if (values.isEmpty()) label else values.joinToString(", ", "$label (", ")")
    val trigger = withValues(description.trigger, description.filters)
    val conditions = if (description.conditions.isEmpty()) {
        ""
    } else {
        " · " + stringResource(R.string.summary_if) + " " + description.conditions.joinToString(", ") { withValues(it.label, it.values) }
    }
    val actions = description.actions.joinToString(", ") { step -> step.applet?.let { "$it: ${step.label}" } ?: step.label }
    val pause = if (description.cooldownMinutes > 0) {
        " · " + stringResource(R.string.summary_pause, cooldownLabel(description.cooldownMinutes))
    } else {
        ""
    }
    return "$trigger$conditions → $actions$pause"
}

/** «без паузы», «15 мин», «1 ч». */
@Composable
fun cooldownLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.pause_none)
    minutes % 60 == 0 -> stringResource(R.string.pause_hours, minutes / 60)
    else -> stringResource(R.string.pause_minutes, minutes)
}

@Composable
fun connectorStateLabel(state: ConnectorState): String = stringResource(
    when (state) {
        ConnectorState.NOT_CONFIGURED -> R.string.connector_not_configured
        ConnectorState.WORKING -> R.string.connector_working
        ConnectorState.BROKEN -> R.string.connector_broken
    },
)

enum class BadgeTone { GOOD, NEUTRAL, BAD }

/** A small coloured label: run status or connector state. */
@Composable
fun StatusBadge(text: String, tone: BadgeTone) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when (tone) {
            BadgeTone.GOOD -> colors.secondaryContainer
            BadgeTone.NEUTRAL -> colors.surfaceVariant
            BadgeTone.BAD -> colors.errorContainer
        },
        contentColor = when (tone) {
            BadgeTone.GOOD -> colors.onSecondaryContainer
            BadgeTone.NEUTRAL -> colors.onSurfaceVariant
            BadgeTone.BAD -> colors.onErrorContainer
        },
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
