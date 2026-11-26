package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.CallState
import java.time.Duration
import java.time.Instant

/** Something a trigger reported: its id, the values of its variables (e.g. `sms.sender`) and when it happened. */
data class Event(val triggerId: String, val values: Map<String, String>, val time: Instant)

/** Port: performs one applet action (e.g. `telegram.send`) with its params already rendered. */
fun interface ActionPerformer {
    /**
     * [key] names this action of this run, the same on every attempt and after a restart (e.g. a notification's tag,
     * so that one run's notification never replaces another's).
     */
    suspend fun perform(params: Map<String, String>, key: String): ActionResult
}

sealed interface ActionResult {
    /** [note] says what happened, e.g. where the message went. */
    data class Done(val note: String? = null) : ActionResult

    /** The action did not happen; [reason] is shown to the user as is. */
    sealed interface Problem : ActionResult {
        val reason: String
    }

    /** Temporary (no network, the service is busy): try again later, not before [after] if the service said so. */
    data class Retry(override val reason: String, val after: Duration? = null) : Problem

    /** Permanent for this run (e.g. the connector is not set up): the run fails, nothing is retried. */
    data class Failed(override val reason: String) : Problem

    /** Permanent for every run: the service rejects the credentials, the connector becomes «Сломан». */
    data class Broken(override val reason: String) : Problem
}

/** Port: whether a connector (e.g. `telegram`) is «Сломан»; it stays so until its setup succeeds again. */
interface ConnectorHealth {
    /** Why the connector is broken; `null` if it is not. */
    fun brokenReason(connectorId: String): String?

    suspend fun markBroken(connectorId: String, reason: String)
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
