package com.sbkubric.robby.core.scenario

/**
 * The scenario list's switch. A scenario is switched on only if it passes the rules of [SaveScenario]: one imported
 * with an applet this build lacks («апплет недоступен») stays off until it is fixed. Switching off always works.
 */
class SwitchScenario(
    private val repository: ScenarioRepository,
    private val validator: ScenarioValidator,
) {
    sealed interface Result {
        /** Switched, or the scenario is gone and there is nothing to switch. */
        data object Switched : Result
        data class Invalid(val problems: List<ScenarioProblem>) : Result
    }

    suspend operator fun invoke(id: String, on: Boolean): Result {
        val scenario = repository.get(id) ?: return Result.Switched
        if (on) {
            val problems = validator.validate(scenario)
            if (problems.isNotEmpty()) return Result.Invalid(problems)
        }
        repository.save(scenario.copy(enabled = on))
        return Result.Switched
    }
}
