package com.sbkubric.robby.applets

import android.content.BroadcastReceiver
import com.sbkubric.robby.AppContainer
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.log.RunStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [prepare] (e.g. setting the next alarm) and hands the events it returns to the engine, from a broadcast receiver.
 * The engine only decides and records the runs, which takes no network, so the broadcast is kept alive just for that;
 * delivery happens in the service ([com.sbkubric.robby.service.DeliveryLoop]). BROADCAST_LIMIT is the backstop that
 * always lets finish() happen within the ~10 s the system allows a receiver.
 */
fun BroadcastReceiver.handleEvents(container: AppContainer, prepare: suspend () -> List<Event>) {
    val pending = goAsync()
    container.appScope.launch {
        try {
            withTimeoutOrNull(BROADCAST_LIMIT) { container.runEvents(prepare()) }
        } finally {
            pending.finish()
        }
    }
}

/** Records the runs of the [events], in order, and wakes delivery if any are to be performed. */
suspend fun AppContainer.runEvents(events: List<Event>) {
    val runs = events.flatMap { handleEvent(it) }
    if (runs.any { it.status == RunStatus.PENDING }) delivery.kick()
}

private val BROADCAST_LIMIT = 9.seconds
