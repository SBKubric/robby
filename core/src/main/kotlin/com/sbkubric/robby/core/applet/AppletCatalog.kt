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

    /** One line for the scenario list, e.g. «Входящее SMS (900) → Telegram: Отправить сообщение в группу». */
    fun summary(scenario: Scenario): String {
        val spec = trigger(scenario.trigger.id)
        val filled = spec?.params.orEmpty()
            .filter { !it.isTemplate }
            .mapNotNull { scenario.trigger.params[it.key]?.trim()?.takeIf(String::isNotEmpty) }
        val trigger = (spec?.label ?: scenario.trigger.id) + if (filled.isEmpty()) "" else filled.joinToString(", ", " (", ")")
        val actions = scenario.actions.joinToString(", ") { action ->
            val label = action(action.id)?.label ?: return@joinToString action.id
            "${appletOf(action.id)?.name}: $label"
        }
        return "$trigger → $actions"
    }
}
