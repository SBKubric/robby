package com.sbkubric.robby.core.scenario

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.template.CommonVariables
import com.sbkubric.robby.core.template.Template

/** Why a scenario cannot be saved. */
sealed interface ScenarioProblem {
    data object NoName : ScenarioProblem
    data object NoTrigger : ScenarioProblem
    data object NoActions : ScenarioProblem

    /** An action's template text is empty. */
    data object EmptyText : ScenarioProblem

    /** The trigger or actions are not in the catalog (e.g. an applet this build does not have). */
    data class UnknownElements(val ids: Set<String>) : ScenarioProblem

    /** Template texts use variables the trigger does not provide. */
    data class UnavailableVariables(val variables: Set<String>) : ScenarioProblem
}

/** The rules a scenario must pass before it is saved. */
class ScenarioValidator(private val catalog: AppletCatalog) {

    fun validate(draft: ScenarioDraft): List<ScenarioProblem> {
        val problems = mutableListOf<ScenarioProblem>()
        if (draft.name.isBlank()) problems += ScenarioProblem.NoName
        if (draft.trigger == null) problems += ScenarioProblem.NoTrigger
        if (draft.actions.isEmpty()) problems += ScenarioProblem.NoActions

        val unknown = listOfNotNull(draft.trigger?.id?.takeIf { catalog.trigger(it) == null }) +
            draft.actions.map { it.id }.filter { catalog.action(it) == null }
        if (unknown.isNotEmpty()) problems += ScenarioProblem.UnknownElements(unknown.toSet())

        val texts = templateTexts(draft)
        if (texts.any { it.isBlank() }) problems += ScenarioProblem.EmptyText

        val trigger = draft.trigger?.let { catalog.trigger(it.id) }
        val available = trigger?.variables.orEmpty().toSet() + CommonVariables.ALL
        val unavailable = texts.flatMap { Template.variablesIn(it) }.toSet() - available
        if (unavailable.isNotEmpty()) problems += ScenarioProblem.UnavailableVariables(unavailable)
        return problems
    }

    private fun templateTexts(draft: ScenarioDraft): List<String> =
        draft.actions.flatMap { action ->
            catalog.action(action.id)?.params.orEmpty().filter { it.isTemplate }.map { action.params[it.key].orEmpty() }
        }
}
