package com.sbkubric.robby.applets

import android.content.BroadcastReceiver
import com.sbkubric.robby.AppContainer
import com.sbkubric.robby.core.engine.Event
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [prepare] (e.g. setting the next alarm) and hands the events it returns to the engine, from a broadcast receiver.
 *
 * Keeps the broadcast alive while the scenarios run, within the ~10 s the system allows a receiver:
 * the engine stops a run after RUN_LIMIT and logs it as timed out; BROADCAST_LIMIT is the backstop
 * that always lets finish() happen. A delivery queue in the service replaces this in slice 6.
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

/**
 * Runs the [events] through the engine side by side, each scenario's run within RUN_LIMIT
 * (so the activity log says «timed out» rather than nothing if an action hangs).
 */
suspend fun AppContainer.runEvents(events: List<Event>) {
    coroutineScope { events.map { async { handleEvent(it, timeLimit = RUN_LIMIT) } }.awaitAll() }
}

private val RUN_LIMIT = 8.seconds
private val BROADCAST_LIMIT = 9.seconds
