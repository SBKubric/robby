package com.sbkubric.robby.core.scenario

import java.util.UUID

/** Validates a draft and stores it; an invalid draft is not stored. */
class SaveScenario(
    private val repository: ScenarioRepository,
    private val validator: ScenarioValidator,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Result {
        data class Saved(val scenario: Scenario) : Result
        data class Invalid(val problems: List<ScenarioProblem>) : Result
    }

    suspend operator fun invoke(draft: ScenarioDraft): Result {
        val problems = validator.validate(draft)
        if (problems.isNotEmpty()) return Result.Invalid(problems)
        val scenario = Scenario(
            id = draft.id ?: newId(),
            name = draft.name.trim(),
            enabled = draft.enabled,
            trigger = checkNotNull(draft.trigger),
            actions = draft.actions,
        )
        repository.save(scenario)
        return Result.Saved(scenario)
    }
}
