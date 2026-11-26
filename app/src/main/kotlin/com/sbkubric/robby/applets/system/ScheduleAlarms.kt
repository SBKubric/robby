package com.sbkubric.robby.applets.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sbkubric.robby.core.applets.SystemSchedule
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * The `system.schedule` trigger on Android: one alarm, always set for the next fire of all schedules ([SystemSchedule]).
 *
 * Exact alarms (`setExactAndAllowWhileIdle`) ring on time even in Doze; they need «Будильники и напоминания»
 * (SCHEDULE_EXACT_ALARM), which the user may deny. Without it the alarm is inexact and may come minutes late:
 * the editor shows a hint. Alarms do not survive a reboot, so the schedule is set again whenever the process starts,
 * the scenarios change, the clock or time zone changes, or the permission is granted.
 */
class ScheduleAlarms(
    private val context: Context,
    private val scenarios: ScenarioRepository,
    private val clock: Clock,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** False if the user has not allowed exact alarms: schedules may then fire late. */
    fun canBeExact(): Boolean = alarms.canScheduleExactAlarms()

    /** While the process lives, every change of the scenarios (saved, switched, deleted) moves the alarm. */
    fun follow(scope: CoroutineScope) {
        scope.launch { scenarios.observeAll().distinctUntilChanged().collect { set(it) } }
    }

    /** Sets the alarm for the first fire after now, or after [notBefore] if that is later. */
    suspend fun reschedule(notBefore: Instant? = null) = set(scenarios.all(), notBefore)

    /** The events of the alarm that was set for [time]: none if the scenarios changed since. */
    suspend fun eventsAt(time: Instant): List<Event> =
        SystemSchedule.next(scenarios.all(), after = time.minusMillis(1).atZone(clock.zone))
            ?.takeIf { it.time == time }
            ?.events
            .orEmpty()

    private fun set(all: List<Scenario>, notBefore: Instant? = null) {
        val after = listOfNotNull(clock.instant(), notBefore).max()
        val fire = SystemSchedule.next(all, after = after.atZone(clock.zone))
        if (fire == null) {
            alarms.cancel(pendingIntent(null))
            return
        }
        val at = fire.time.toEpochMilli()
        val intent = pendingIntent(at)
        // The permission can be revoked between the check and the call: then the alarm is inexact.
        val exact = canBeExact() && runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }.isSuccess
        if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        Log.i("Robby", "Next schedule at ${fire.time} (exact: $exact)")
    }

    /** The one alarm's intent; extras do not take part in matching, so cancel works with any [at]. */
    private fun pendingIntent(at: Long?): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_FIRE)
        if (at != null) intent.putExtra(ScheduleReceiver.EXTRA_TIME, at)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
