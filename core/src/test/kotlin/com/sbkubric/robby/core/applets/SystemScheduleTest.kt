package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.engine.ActionPerformer
import com.sbkubric.robby.core.engine.ActionResult
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemScheduleTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val everyDay = DayOfWeek.entries.toSet()
    private val workdays = everyDay - setOf(SATURDAY, SUNDAY)

    private fun at(text: String, zone: ZoneId = moscow) = LocalDateTime.parse(text).atZone(zone)

    private fun nine(days: Set<DayOfWeek>, after: ZonedDateTime) =
        SystemSchedule.nextFire(days, LocalTime.of(9, 0), after)

    // 2026-11-26 is a Thursday.

    @Test
    fun `daily schedule fires today if the time is still ahead, otherwise tomorrow`() {
        assertEquals(at("2026-11-26T09:00"), nine(everyDay, after = at("2026-11-26T08:00")))
        assertEquals(at("2026-11-27T09:00"), nine(everyDay, after = at("2026-11-26T09:00")))
    }

    @Test
    fun `weekday schedule skips the days not chosen`() {
        assertEquals(at("2026-11-30T09:00"), nine(workdays, after = at("2026-11-27T10:00")))
        assertEquals(at("2026-11-28T09:00"), nine(setOf(SATURDAY), after = at("2026-11-26T08:00")))
        assertEquals(at("2026-12-03T09:00"), nine(setOf(DayOfWeek.THURSDAY), after = at("2026-11-26T09:00")))
    }

    @Test
    fun `time that does not exist on the spring DST change fires when the clock jumps`() {
        // Berlin, 2026-03-29: 02:00 CET jumps to 03:00 CEST, so 02:30 does not exist.
        val fire = SystemSchedule.nextFire(everyDay, LocalTime.of(2, 30), after = at("2026-03-29T01:00", berlin))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), fire.toInstant())
        assertEquals(at("2026-03-30T02:30", berlin), SystemSchedule.nextFire(everyDay, LocalTime.of(2, 30), after = fire))
    }

    @Test
    fun `time that happens twice on the autumn DST change fires once`() {
        // Berlin, 2026-10-25: 03:00 CEST falls back to 02:00 CET, so 02:30 happens twice.
        val first = SystemSchedule.nextFire(everyDay, LocalTime.of(2, 30), after = at("2026-10-25T01:00", berlin))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first.toInstant())
        assertEquals(at("2026-10-26T02:30", berlin), SystemSchedule.nextFire(everyDay, LocalTime.of(2, 30), after = first))
    }

    private fun scheduled(id: String, days: String, time: String, enabled: Boolean = true) = Scenario(
        id = id,
        name = "Пульс $id",
        enabled = enabled,
        trigger = Trigger("system.schedule", mapOf("days" to days, "time" to time)),
        actions = listOf(Action("telegram.send", mapOf("text" to "{device} на связи, {now}"))),
    )

    @Test
    fun `next fire is the earliest schedule of the enabled scenarios, one event per slot`() {
        val scenarios = listOf(
            scheduled("a", "1,2,3,4,5,6,7", "09:00"),
            scheduled("b", "4", "9:00"),
            scheduled("c", "1,2,3,4,5,6,7", "08:00", enabled = false),
            scheduled("d", "1,2,3,4,5,6,7", "10:00"),
            scheduled("sms", "", "").copy(trigger = Trigger("sms.in")),
        )

        val next = SystemSchedule.next(scenarios, after = at("2026-11-26T07:00"))

        val nineToday = at("2026-11-26T09:00").toInstant()
        assertEquals(
            SystemSchedule.Fire(
                nineToday,
                listOf(Event("system.schedule", mapOf("system.slot.day" to "4", "system.slot.time" to "09:00"), nineToday)),
            ),
            next,
        )
    }

    @Test
    fun `no enabled schedule means no next fire`() {
        assertNull(SystemSchedule.next(listOf(scheduled("a", "1", "09:00", enabled = false), scheduled("b", "", "09:00")), at("2026-11-26T07:00")))
    }

    @Test
    fun `heartbeat every day at 09_00 sends to Telegram, other schedules stay quiet`() = runBlocking {
        val sent = mutableListOf<String>()
        val scenarios = listOf(
            scheduled("pulse", "1,2,3,4,5,6,7", "09:00"),
            scheduled("later", "1,2,3,4,5,6,7", "10:00"),
            scheduled("weekend", "6,7", "09:00"),
        )
        val fire = SystemSchedule.next(scenarios, after = at("2026-11-26T08:59"))!!
        val engine = HandleEvent(
            scenarios = FakeScenarioRepository(*scenarios.toTypedArray()),
            catalog = BuiltInApplets.catalog,
            performers = mapOf("telegram.send" to ActionPerformer { sent += it.getValue("text"); ActionResult.Done() }),
            log = FakeActivityLog(),
            deviceName = { "Pixel дома" },
            clock = Clock.fixed(fire.time, moscow),
        )

        val runs = fire.events.flatMap { engine(it) }

        assertEquals(listOf("pulse"), runs.map { it.scenarioId })
        assertEquals(listOf("Pixel дома на связи, 26.11.2026 09:00"), sent)
    }

    @Test
    fun `friday to monday across a weekend`() {
        assertEquals(MONDAY, nine(workdays, after = at("2026-11-27T09:00")).dayOfWeek)
        assertEquals(FRIDAY, nine(setOf(FRIDAY), after = at("2026-11-26T09:00")).dayOfWeek)
    }
}
