package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.CallState
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

/** Port: the server phone's battery now, for conditions; `null` if the phone does not report it. */
fun interface BatteryStateProvider {
    fun batteryState(): BatteryState?
}

/** Port: the server phone's calls now, for conditions; `null` if the phone does not report them. */
fun interface CallStateProvider {
    fun callState(): CallState?
}

/** Port: the name of the contact with this phone [number]; `null` if there is none or the contacts cannot be read. */
fun interface ContactNames {
    fun nameOf(number: String): String?
}
