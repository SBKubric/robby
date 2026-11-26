package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.ServerPhoneState
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunAction
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.template.CommonVariables
import com.sbkubric.robby.core.template.Template
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * The engine's decision: an event runs every enabled scenario whose trigger it matches and whose filters it passes.
 * Each such scenario becomes a [Run]: a condition that is not met, or the scenario's pause, skips it;
 * otherwise it is recorded as [RunStatus.PENDING] with a snapshot of its actions, rendered with the event's variables,
 * for [DeliverRuns] to perform. Nothing here waits for the network, so a broadcast receiver can call it directly.
 * Every run goes to the activity log, skipped ones too; filtered-out events are not logged.
 */
class HandleEvent(
    private val scenarios: ScenarioRepository,
    private val catalog: AppletCatalog,
    private val log: ActivityLog,
    private val deviceName: DeviceNameProvider,
    private val battery: BatteryStateProvider,
    private val call: CallStateProvider,
    private val clock: Clock,
) {
    // The pause looks at the log and the run is added to it: one decision at a time, so two events at once cannot both pass.
    private val deciding = Mutex()

    /** Returns the recorded runs, with their ids. */
    suspend operator fun invoke(event: Event): List<Run> = deciding.withLock { decide(event) }

    private suspend fun decide(event: Event): List<Run> {
        val trigger = catalog.trigger(event.triggerId) ?: return emptyList()
        val matching = scenarios.all()
            .filter { it.enabled && it.trigger.id == event.triggerId && trigger.matches(it.trigger.params, event.values) }
        if (matching.isEmpty()) return emptyList()

        val state = ServerPhoneState(time = event.time.atZone(clock.zone), battery = battery.batteryState(), call = call.callState())
        val values = event.values + mapOf(
            CommonVariables.NOW to TIME_FORMAT.format(state.time),
            CommonVariables.DEVICE to deviceName.deviceName(),
        )
        val runs = matching.map { scenario ->
            log.add(skippedRun(scenario, event, state) ?: scenario.run(event, RunStatus.PENDING, actions = snapshot(scenario, values)))
        }
        log.prune(olderThan = clock.instant().minus(LOG_MAX_AGE), keepNewest = LOG_MAX_RUNS)
        return runs
    }

    /** A skipped run if a condition is not met (the first one, in order) or the scenario is within its pause. */
    private suspend fun skippedRun(scenario: Scenario, event: Event, state: ServerPhoneState): Run? {
        val unmet = scenario.conditions.firstOrNull { catalog.condition(it.id)?.isMet(it.params, state) != true }
        if (unmet != null) return scenario.run(event, RunStatus.SKIPPED, unmet.id, RunReason.CONDITION_NOT_MET)

        if (scenario.cooldownMinutes > 0) {
            val last = log.lastExecuted(scenario.id)
            if (last != null && event.time < last.time + scenario.cooldown) {
                return scenario.run(event, RunStatus.SKIPPED, null, RunReason.COOLDOWN)
            }
        }
        return null
    }

    /** The scenario's actions as they are now, their template params rendered: what delivery will perform. */
    private fun snapshot(scenario: Scenario, values: Map<String, String>): List<RunAction> =
        scenario.actions.map { action ->
            val templates = catalog.action(action.id)?.params.orEmpty().filter { it.isTemplate }.map { it.key }.toSet()
            RunAction(action.id, action.params.mapValues { (key, value) -> if (key in templates) Template.render(value, values) else value })
        }

    private fun Scenario.run(
        event: Event,
        status: RunStatus,
        message: String? = null,
        reason: RunReason? = null,
        actions: List<RunAction> = emptyList(),
    ) = Run(id, name, event.time, status, message, reason, actions = actions)

    companion object {
        /** The activity log keeps 30 days of runs, but no more than 2000. */
        val LOG_MAX_AGE: Duration = Duration.ofDays(30)
        const val LOG_MAX_RUNS = 2000

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
