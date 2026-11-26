package com.sbkubric.robby.applets.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.applets.handleEvents
import java.time.Instant

/**
 * The schedule alarm rang: sets the next one, then runs the `system.schedule` events of the time it was set for.
 * A clock or time zone change, or newly granted exact alarms, only move the alarm.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val alarms = container.scheduleAlarms
        when (intent.action) {
            ACTION_FIRE -> {
                val time = intent.getLongExtra(EXTRA_TIME, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
                handleEvents(container) {
                    // The next alarm first: it must be set even if the scenarios use up the broadcast's time.
                    alarms.reschedule(notBefore = time)
                    time?.let { alarms.eventsAt(it) }.orEmpty()
                }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> handleEvents(container) {
                alarms.reschedule()
                emptyList()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.sbkubric.robby.action.SCHEDULE_FIRE"
        const val EXTRA_TIME = "time"
    }
}
