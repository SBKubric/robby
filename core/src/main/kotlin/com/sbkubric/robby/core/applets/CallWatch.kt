package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.engine.ContactNames
import com.sbkubric.robby.core.engine.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Turns the phone's call state broadcasts, in order, into the Call applet's trigger events:
 * [CallApplet.INCOMING] when a call starts ringing and [CallApplet.MISSED] when it stops ringing and the phone goes idle
 * without the call being taken (rejected counts as missed). Both carry the number and the contact's name ([ContactNames]).
 *
 * Android sends every state twice to an app that may read numbers: once without the number and once with it,
 * in either order. So when a number is expected, a ringing without one waits for the broadcast with it,
 * and broadcasts of a state already seen are ignored. If the number never comes (some OEM builds), the incoming call
 * is reported without it when the ringing ends, late but not lost. A withheld number comes as an empty one.
 *
 * A call waiting during another call makes the state RINGING: it is reported as incoming, and as missed only if
 * the phone goes idle without taking it; Android does not tell a waiting call rejected from one taken, so that is not
 * reported as missed. Outgoing calls report nothing.
 *
 * The ringing call is kept through [CallWatchStore], since the system may start the process afresh for each broadcast.
 * One remembered for longer than [RINGING_LIMIT] is stale (e.g. the phone rebooted while it rang, and no idle state
 * came) and is forgotten: it is neither missed nor taken for the ringing of the next call.
 */
class CallWatch(
    private val store: CallWatchStore,
    private val contacts: ContactNames,
) {
    /** The call that is ringing: its [number] (`null` while unknown), whether it was [reported] as incoming, and since when. */
    @Serializable
    data class Ringing(val number: String?, val reported: Boolean, val sinceMillis: Long)

    private val mutex = Mutex()

    /**
     * The events of a broadcast of the call [state] at [time], with the [number] it carries (`null` if none).
     * [numberExpected]: Robby may read numbers, so a broadcast with the number comes too.
     */
    suspend fun onReading(state: CallState, number: String?, time: Instant, numberExpected: Boolean): List<Event> = mutex.withLock {
        val stored = store.load()
        val ringing = stored?.takeUnless { time.toEpochMilli() - it.sinceMillis > RINGING_LIMIT.toMillis() }
        val events = mutableListOf<Event>()
        val after = when (state) {
            CallState.RINGING -> {
                val current = ringing ?: Ringing(number = null, reported = false, sinceMillis = time.toEpochMilli())
                val known = number ?: current.number
                val report = !current.reported && (known != null || !numberExpected)
                if (report) events += event(CallApplet.INCOMING, known, time)
                current.copy(number = known, reported = current.reported || report)
            }
            CallState.OFFHOOK, CallState.IDLE -> {
                if (ringing != null && !ringing.reported) events += event(CallApplet.INCOMING, ringing.number, time)
                if (ringing != null && state == CallState.IDLE) events += event(CallApplet.MISSED, ringing.number, time)
                null
            }
        }
        if (after != stored) store.save(after)
        events
    }

    private fun event(trigger: String, number: String?, time: Instant): Event {
        val contact = number?.takeIf { it.isNotBlank() }?.let(contacts::nameOf)
        return Event(trigger, mapOf(CallApplet.NUMBER to number.orEmpty(), CallApplet.CONTACT to contact.orEmpty()), time)
    }

    companion object {
        /** Networks stop ringing within a few minutes; a ringing remembered for longer is stale. */
        val RINGING_LIMIT: Duration = Duration.ofMinutes(5)
    }
}

/** Port: keeps [CallWatch]'s ringing call across process restarts. */
interface CallWatchStore {
    /** The call that was ringing; `null` if none was. */
    fun load(): CallWatch.Ringing?

    /** `null`: no call is ringing. */
    fun save(ringing: CallWatch.Ringing?)
}
