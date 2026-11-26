package com.sbkubric.robby.core.applet

import com.sbkubric.robby.core.scenario.Scenario

/** Every applet the app has. The editor and the engine know applets only through it. */
class AppletCatalog(val applets: List<Applet>) {

    private val triggers = applets.flatMap { it.triggers }.associateBy { it.id }
    private val actions = applets.flatMap { it.actions }.associateBy { it.id }

    init {
        val ids = applets.map { it.id } + applets.flatMap { a -> a.triggers.map { it.id } + a.actions.map { it.id } }
        require(ids.size == ids.toSet().size) { "Duplicate applet element ids: $ids" }
    }

    fun trigger(id: String): TriggerSpec? = triggers[id]

    fun action(id: String): ActionSpec? = actions[id]

    /** The applet that offers the trigger or action with this id. */
    fun appletOf(elementId: String): Applet? =
        applets.firstOrNull { a -> a.triggers.any { it.id == elementId } || a.actions.any { it.id == elementId } }

    fun appletsWithTriggers(): List<Applet> = applets.filter { it.triggers.isNotEmpty() }

    fun appletsWithActions(): List<Applet> = applets.filter { it.actions.isNotEmpty() }

    /** What the scenario list shows about a scenario, built from the catalog alone; the UI lays it out. */
    fun describe(scenario: Scenario): ScenarioDescription {
        val spec = trigger(scenario.trigger.id)
        return ScenarioDescription(
            trigger = spec?.label ?: scenario.trigger.id,
            filters = spec?.params.orEmpty()
                .filter { !it.isTemplate }
                .mapNotNull { scenario.trigger.params[it.key]?.trim()?.takeIf(String::isNotEmpty) },
            actions = scenario.actions.map { action ->
                val label = action(action.id)?.label
                ScenarioDescription.Step(applet = label?.let { appletOf(action.id)?.name }, label = label ?: action.id)
            },
        )
    }
}

/** Labels of a scenario's trigger, its filled filters and its actions (an unknown element shows its id). */
data class ScenarioDescription(val trigger: String, val filters: List<String>, val actions: List<Step>) {
    data class Step(val applet: String?, val label: String)
}
