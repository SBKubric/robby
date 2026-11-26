package com.sbkubric.robby.core.applet

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
}
