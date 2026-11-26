package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.ScenarioDraft
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioValidator
import com.sbkubric.robby.core.template.CommonVariables
import java.time.Clock

/**
 * «Тест» in the editor: records one run of the draft as it is now, saved or not, as if its trigger had fired with
 * the sample values of its variables ([com.sbkubric.robby.core.applet.Variable.sample]); `{now}` and `{device}` are real.
 * Filters, conditions and the pause are not checked. The run is [Run.test] and [RunStatus.PENDING]: [DeliverRuns]
 * performs it like any other, and the pause does not count from it.
 */
class TestScenario(
    private val validator: ScenarioValidator,
    private val catalog: AppletCatalog,
    private val log: ActivityLog,
    private val deviceName: DeviceNameProvider,
    private val clock: Clock,
) {
    sealed interface Result {
        /** The test run, with its id; delivery has yet to perform it. */
        data class Recorded(val run: Run) : Result

        /** The draft could not be saved as it is, so it is not tested either. */
        data class Invalid(val problems: List<ScenarioProblem>) : Result
    }

    suspend operator fun invoke(draft: ScenarioDraft): Result {
        val problems = validator.validate(draft)
        if (problems.isNotEmpty()) return Result.Invalid(problems)
        val trigger = checkNotNull(draft.trigger?.let { catalog.trigger(it.id) })
        val time = clock.instant()
        val values = trigger.variables.associate { it.name to it.sample } +
            CommonVariables.values(time.atZone(clock.zone), deviceName.deviceName())
        val run = Run(
            scenarioId = draft.id.orEmpty(),
            scenarioName = draft.name.trim(),
            time = time,
            status = RunStatus.PENDING,
            message = null,
            actions = catalog.snapshot(draft.actions, values),
            test = true,
        )
        return Result.Recorded(log.add(run))
    }
}
