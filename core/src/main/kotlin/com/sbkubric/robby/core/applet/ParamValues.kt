package com.sbkubric.robby.core.applet

import java.time.DayOfWeek
import java.time.LocalTime

/** Reading and writing the text of [ParamKind.TIME] and [ParamKind.WEEKDAYS] params. */
object ParamValues {

    private val timePattern = Regex("""(\d{1,2}):(\d{2})""")

    /** `9:05` or `09:05`; `null` if it is not a time of day. */
    fun time(text: String): LocalTime? {
        val match = timePattern.matchEntire(text.trim()) ?: return null
        val (hour, minute) = match.destructured
        return runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()
    }

    /** `09:05`. */
    fun time(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** `1,3,5` → Mon, Wed, Fri; `null` if the text is empty or not a list of ISO weekday numbers. */
    fun weekdays(text: String): Set<DayOfWeek>? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n in 1..7 } ?: return null }
        return numbers.map { DayOfWeek.of(it) }.toSet()
    }

    /** Mon, Wed, Fri → `1,3,5`. */
    fun weekdays(days: Set<DayOfWeek>): String = days.sorted().joinToString(",") { it.value.toString() }

    val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    val WORKDAYS: Set<DayOfWeek> = EVERY_DAY - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private val shortNames = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** «пн». */
    fun shortName(day: DayOfWeek): String = shortNames[day.value - 1]

    /** «каждый день», «по будням», «по выходным» or «пн, ср, пт». */
    fun describeWeekdays(days: Set<DayOfWeek>): String = when (days) {
        EVERY_DAY -> "каждый день"
        WORKDAYS -> "по будням"
        WEEKEND -> "по выходным"
        else -> days.sorted().joinToString(", ") { shortName(it) }
    }
}
