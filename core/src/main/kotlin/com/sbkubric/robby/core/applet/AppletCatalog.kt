package com.sbkubric.robby.core.applet

import com.sbkubric.robby.core.scenario.Scenario

/** Every applet the app has. The editor and the engine know applets only through it. */
class AppletCatalog(val applets: List<Applet>) {

    private val triggers = applets.flatMap { it.triggers }.associateBy { it.id }
    private val conditions = applets.flatMap { it.conditions }.associateBy { it.id }
    private val actions = applets.flatMap { it.actions }.associateBy { it.id }

    init {
        val ids = applets.map { it.id } + applets.flatMap { it.elementIds() }
        require(ids.size == ids.toSet().size) { "Duplicate applet element ids: $ids" }
    }

    fun trigger(id: String): TriggerSpec? = triggers[id]

    fun condition(id: String): ConditionSpec? = conditions[id]

    fun action(id: String): ActionSpec? = actions[id]

    /** The applet that offers the trigger, condition or action with this id. */
    fun appletOf(elementId: String): Applet? = applets.firstOrNull { elementId in it.elementIds() }

    fun appletsWithTriggers(): List<Applet> = applets.filter { it.triggers.isNotEmpty() }

    fun appletsWithConditions(): List<Applet> = applets.filter { it.conditions.isNotEmpty() }

    fun appletsWithActions(): List<Applet> = applets.filter { it.actions.isNotEmpty() }

    /** What the scenario list shows about a scenario, built from the catalog alone; the UI lays it out. */
    fun describe(scenario: Scenario): ScenarioDescription {
        val spec = trigger(scenario.trigger.id)
        return ScenarioDescription(
            trigger = spec?.label ?: scenario.trigger.id,
            filters = filledValues(spec?.params.orEmpty(), scenario.trigger.params),
            actions = scenario.actions.map { action ->
                val label = action(action.id)?.label
                ScenarioDescription.Step(applet = label?.let { appletOf(action.id)?.name }, label = label ?: action.id)
            },
            conditions = scenario.conditions.map { condition ->
                val conditionSpec = condition(condition.id)
                ScenarioDescription.Check(
                    label = conditionSpec?.label ?: condition.id,
                    values = filledValues(conditionSpec?.params.orEmpty(), condition.params),
                )
            },
            cooldownMinutes = scenario.cooldownMinutes,
        )
    }

    /** The non-template params that are filled, as the user reads them (weekdays as «по будням»). */
    private fun filledValues(specs: List<ParamSpec>, params: Map<String, String>): List<String> =
        specs.filter { !it.isTemplate }.mapNotNull { spec ->
            val value = params[spec.key]?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            when (spec.kind) {
                ParamKind.WEEKDAYS -> ParamValues.weekdays(value)?.let(ParamValues::describeWeekdays) ?: value
                ParamKind.TIME -> ParamValues.time(value)?.let(ParamValues::time) ?: value
                ParamKind.TEXT, ParamKind.TEMPLATE -> value
            }
        }

    private fun Applet.elementIds() = triggers.map { it.id } + conditions.map { it.id } + actions.map { it.id }
}

/**
 * Labels of a scenario's trigger and its filled filters, its conditions, its actions with their applets,
 * and its pause; an unknown element shows its id.
 */
data class ScenarioDescription(
    val trigger: String,
    val filters: List<String>,
    val actions: List<Step>,
    val conditions: List<Check> = emptyList(),
    val cooldownMinutes: Int = 0,
) {
    data class Step(val applet: String?, val label: String)

    data class Check(val label: String, val values: List<String>)
}
