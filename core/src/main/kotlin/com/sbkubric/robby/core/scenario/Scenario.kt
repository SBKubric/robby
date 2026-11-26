package com.sbkubric.robby.core.scenario

import kotlinx.serialization.Serializable

/** «Когда [триггер], то [действия]», switched on and off by the user. */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val actions: List<Action>,
)

/** The scenario's trigger: an applet trigger id (e.g. `sms.in`) and its filter params. */
@Serializable
data class Trigger(val id: String, val params: Map<String, String> = emptyMap())

/** One step of the scenario: an applet action id (e.g. `telegram.send`) and its params. */
@Serializable
data class Action(val id: String, val params: Map<String, String> = emptyMap())

/** A scenario being edited: it may still lack a trigger and an id. */
data class ScenarioDraft(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger? = null,
    val actions: List<Action> = emptyList(),
) {
    companion object {
        fun of(scenario: Scenario) =
            ScenarioDraft(scenario.id, scenario.name, scenario.enabled, scenario.trigger, scenario.actions)
    }
}
