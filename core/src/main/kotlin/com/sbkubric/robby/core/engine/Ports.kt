package com.sbkubric.robby.core.engine

import java.time.Instant

/** Something a trigger reported: its id, the values of its variables (e.g. `sms.sender`) and when it happened. */
data class Event(val triggerId: String, val values: Map<String, String>, val time: Instant)

/** Port: performs one applet action (e.g. `telegram.send`) with its params already rendered. */
fun interface ActionPerformer {
    suspend fun perform(params: Map<String, String>): ActionResult
}

sealed interface ActionResult {
    /** [note] says what happened, e.g. where the message went. */
    data class Done(val note: String? = null) : ActionResult

    data class Failed(val reason: String) : ActionResult
}

/** Port: the server phone's name for `{device}`. */
fun interface DeviceNameProvider {
    fun deviceName(): String
}
