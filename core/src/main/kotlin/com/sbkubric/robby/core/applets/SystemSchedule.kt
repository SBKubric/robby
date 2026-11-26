package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.ParamValues
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.scenario.Scenario
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When the `system.schedule` triggers fire. The `:app` alarm asks for [next], sets one alarm for its time,
 * and when it rings asks again for the events of that time and hands them to the engine.
 */
object SystemSchedule {

    /** An instant at which schedules fire, with one event per distinct slot (weekday and time) due then. */
    data class Fire(val time: Instant, val events: List<Event>)

    /**
     * The first time strictly after [after], in its zone, that falls on one of [days] at [time].
     * A time skipped by a DST change fires when the clock jumps (02:30 → 03:30); a time that happens twice fires once.
     */
    fun nextFire(days: Set<DayOfWeek>, time: LocalTime, after: ZonedDateTime): ZonedDateTime {
        require(days.isNotEmpty()) { "A schedule needs at least one day" }
        val start = after.toLocalDate()
        return (0L..7L).asSequence()
            .map { start.plusDays(it) }
            .filter { it.dayOfWeek in days }
            .map { ZonedDateTime.of(it, time, after.zone) }
            .first { it.isAfter(after) }
    }

    /** The earliest fire of the enabled scenarios' schedules after [after]; `null` if none has a readable schedule. */
    fun next(scenarios: List<Scenario>, after: ZonedDateTime): Fire? {
        val slots = scenarios
            .filter { it.enabled && it.trigger.id == SystemApplet.SCHEDULE }
            .mapNotNull { scenario ->
                val days = ParamValues.weekdays(scenario.trigger.params[SystemApplet.P_DAYS].orEmpty())
                val time = ParamValues.time(scenario.trigger.params[SystemApplet.P_TIME].orEmpty())
                if (days == null || time == null) null else nextFire(days, time, after) to time
            }
        val time = slots.minOfOrNull { (fire, _) -> fire.toInstant() } ?: return null
        val events = slots
            .filter { (fire, _) -> fire.toInstant() == time }
            .map { (fire, slotTime) -> fire.dayOfWeek to slotTime }
            .distinct()
            .map { (day, slotTime) ->
                Event(
                    triggerId = SystemApplet.SCHEDULE,
                    values = mapOf(SystemApplet.SLOT_DAY to day.value.toString(), SystemApplet.SLOT_TIME to ParamValues.time(slotTime)),
                    time = time,
                )
            }
        return Fire(time, events)
    }
}
