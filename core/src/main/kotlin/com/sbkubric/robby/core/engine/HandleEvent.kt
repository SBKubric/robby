package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.ServerPhoneState
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.template.CommonVariables
import com.sbkubric.robby.core.template.Template
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import kotlin.time.Duration as TimeLimit

/**
 * The engine: an event runs every enabled scenario whose trigger it matches and whose filters it passes.
 * Each such scenario becomes a [Run]: a condition that is not met, or the scenario's pause, skips it;
 * otherwise its actions are performed in order and the first failure stops it.
 * Every run goes to the activity log, skipped ones too; filtered-out events are not logged.
 */
class HandleEvent(
    private val scenarios: ScenarioRepository,
    private val catalog: AppletCatalog,
    /** Action performers by action id. */
    private val performers: Map<String, ActionPerformer>,
    private val log: ActivityLog,
    private val deviceName: DeviceNameProvider,
    private val clock: Clock,
) {
    /**
     * Matching scenarios run side by side, each within [timeLimit]: a run that does not finish in time
     * is stopped and logged as [RunReason.TIMED_OUT], so the log stays truthful when the caller's time is short
     * (a broadcast receiver has about 10 s).
     */
    suspend operator fun invoke(event: Event, timeLimit: TimeLimit = TimeLimit.INFINITE): List<Run> {
        val trigger = catalog.trigger(event.triggerId) ?: return emptyList()
        val matching = scenarios.all()
            .filter { it.enabled && it.trigger.id == event.triggerId && trigger.matches(it.trigger.params, event.values) }
        if (matching.isEmpty()) return emptyList()

        val state = ServerPhoneState(time = event.time.atZone(clock.zone))
        val values = event.values + mapOf(
            CommonVariables.NOW to TIME_FORMAT.format(state.time),
            CommonVariables.DEVICE to deviceName.deviceName(),
        )
        val runs = coroutineScope {
            matching.map { scenario ->
                async {
                    withTimeoutOrNull(timeLimit) { skippedRun(scenario, event, state) ?: run(scenario, event, values) }
                        ?: scenario.run(event, RunStatus.FAILED, null, RunReason.TIMED_OUT)
                }
            }.awaitAll()
        }
        runs.forEach { log.add(it) }
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

    private suspend fun run(scenario: Scenario, event: Event, values: Map<String, String>): Run {
        val notes = mutableListOf<String>()
        for (action in scenario.actions) {
            val performer = performers[action.id]
                ?: return scenario.run(event, RunStatus.FAILED, action.id, RunReason.NO_PERFORMER)
            when (val result = perform(performer, action, values)) {
                is ActionResult.Done -> result.note?.let { notes += it }
                is ActionResult.Failed -> return scenario.run(event, RunStatus.FAILED, result.reason, null)
            }
        }
        return scenario.run(event, RunStatus.DONE, notes.joinToString("; ").ifEmpty { null }, null)
    }

    private suspend fun perform(performer: ActionPerformer, action: Action, values: Map<String, String>): ActionResult {
        val templates = catalog.action(action.id)?.params.orEmpty().filter { it.isTemplate }.map { it.key }.toSet()
        val params = action.params.mapValues { (key, value) -> if (key in templates) Template.render(value, values) else value }
        return try {
            performer.perform(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun Scenario.run(event: Event, status: RunStatus, message: String?, reason: RunReason?) =
        Run(id, name, event.time, status, message, reason)

    companion object {
        /** The activity log keeps 30 days of runs, but no more than 2000. */
        val LOG_MAX_AGE: Duration = Duration.ofDays(30)
        const val LOG_MAX_RUNS = 2000

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
