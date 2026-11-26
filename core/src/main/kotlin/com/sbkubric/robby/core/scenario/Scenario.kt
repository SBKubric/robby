package com.sbkubric.robby.core.scenario

import kotlinx.serialization.Serializable
import java.time.Duration

/** «Когда [триггер] и [условия], то [действия]», switched on and off by the user. */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    /** All must be met at the time of the event (AND); an empty list lets every event through. */
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
    /** «Пауза»: after a run, further events within this many minutes are skipped; 0 is no pause. */
    val cooldownMinutes: Int = 0,
) {
    val cooldown: Duration get() = Duration.ofMinutes(cooldownMinutes.toLong())

    companion object {
        /** The pauses the editor offers: none, 15 min, 1 h, 4 h. */
        val COOLDOWN_CHOICES = listOf(0, 15, 60, 240)
    }
}

/** The scenario's trigger: an applet trigger id (e.g. `sms.in`) and its filter params. */
@Serializable
data class Trigger(val id: String, val params: Map<String, String> = emptyMap())

/** A check of the server phone's state: an applet condition id (e.g. `system.window`) and its params. */
@Serializable
data class Condition(val id: String, val params: Map<String, String> = emptyMap())

/** One step of the scenario: an applet action id (e.g. `telegram.send`) and its params. */
@Serializable
data class Action(val id: String, val params: Map<String, String> = emptyMap())

/** A scenario being edited: it may still lack a trigger and an id. */
data class ScenarioDraft(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger? = null,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    val cooldownMinutes: Int = 0,
) {
    companion object {
        fun of(scenario: Scenario) = ScenarioDraft(
            scenario.id, scenario.name, scenario.enabled, scenario.trigger, scenario.conditions, scenario.actions, scenario.cooldownMinutes,
        )
    }
}
