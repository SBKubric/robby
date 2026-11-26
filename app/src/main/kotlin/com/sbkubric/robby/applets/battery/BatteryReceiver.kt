package com.sbkubric.robby.applets.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.sbkubric.robby.AppContainer
import com.sbkubric.robby.applets.runEvents
import com.sbkubric.robby.core.applet.BatteryState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The Battery applet's events. The system sends `ACTION_BATTERY_CHANGED` (level, temperature, plug) only to a receiver
 * registered in code, so [com.sbkubric.robby.service.RobbyService] registers this one while it runs.
 * Registering delivers the current (sticky) reading at once: the watch takes it as its start.
 *
 * Readings arrive on the main thread and are handed, in order, to one coroutine that asks the watch for events;
 * the scenarios of those events run separately, so a slow action does not hold up the next reading.
 * One instance per [register]: [unregister] ends it.
 */
class BatteryReceiver(private val container: AppContainer) : BroadcastReceiver() {

    private val readings = Channel<BatteryState>(Channel.UNLIMITED)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
        intent.toBatteryState()?.let { readings.trySend(it) }
    }

    fun register(context: Context) {
        container.appScope.launch {
            for (reading in readings) {
                val events = container.batteryWatch.onReading(reading, container.clock.instant())
                if (events.isNotEmpty()) container.appScope.launch { container.runEvents(events) }
            }
        }
        context.registerReceiver(this, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister(context: Context) {
        context.unregisterReceiver(this)
        readings.close() // ends the loop once the readings already received are handled
    }
}
