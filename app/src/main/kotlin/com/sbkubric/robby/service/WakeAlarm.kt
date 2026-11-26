package com.sbkubric.robby.service

import android.app.AlarmManager
import android.app.PendingIntent

/**
 * Wakes the phone at [atMillis] even in Doze: exactly if the user allowed «Будильники и напоминания»
 * (SCHEDULE_EXACT_ALARM), otherwise inexactly, which may come some minutes late. Returns whether it is exact.
 * The permission can be revoked between the check and the call: then the alarm is inexact.
 */
fun AlarmManager.wakeAt(atMillis: Long, intent: PendingIntent): Boolean {
    val exact = canScheduleExactAlarms() && runCatching { setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent) }.isSuccess
    if (!exact) setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
    return exact
}
