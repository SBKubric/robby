package com.sbkubric.robby.applets.call

import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applets.CallWatch
import com.sbkubric.robby.core.engine.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Hands the call state broadcasts to the [CallWatch] strictly in the order they arrived: one queue, one consumer.
 * A short call hung up by the caller brings RINGING and IDLE almost together; handled the other way round,
 * the missed call would be lost. The scenarios of the events run separately, like the battery's.
 */
class CallReadings(private val watch: CallWatch, scope: CoroutineScope) {

    private class Reading(
        val state: CallState,
        val number: String?,
        val time: Instant,
        val numberExpected: Boolean,
        val events: CompletableDeferred<List<Event>>,
    )

    private val queue = Channel<Reading>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (reading in queue) {
                try {
                    reading.events.complete(watch.onReading(reading.state, reading.number, reading.time, reading.numberExpected))
                } catch (e: Exception) {
                    reading.events.completeExceptionally(e) // fails this broadcast only; the queue goes on
                }
            }
        }
    }

    /** Queues a reading; call it in `onReceive`, which the system calls in broadcast order. Completes with its events. */
    fun add(state: CallState, number: String?, time: Instant, numberExpected: Boolean): Deferred<List<Event>> =
        CompletableDeferred<List<Event>>().also { queue.trySend(Reading(state, number, time, numberExpected, it)) }
}
