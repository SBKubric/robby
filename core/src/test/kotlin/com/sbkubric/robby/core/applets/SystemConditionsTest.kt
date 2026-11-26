package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.ServerPhoneState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemConditionsTest {

    private val catalog = BuiltInApplets.catalog
    private val zone = ZoneId.of("Europe/Moscow")

    /** 2026-11-26 is a Thursday. */
    private fun at(time: String, date: String = "2026-11-26") =
        ServerPhoneState(time = ZonedDateTime.parse("${date}T$time:00+03:00[Europe/Moscow]").withZoneSameInstant(zone))

    private fun window(from: String, to: String, time: String) =
        catalog.condition("system.window")!!.isMet(mapOf("from" to from, "to" to to), at(time))

    private fun days(days: String, date: String) =
        catalog.condition("system.days")!!.isMet(mapOf("days" to days), at("12:00", date))

    @Test
    fun `time window includes its start and excludes its end`() {
        assertEquals(
            listOf(false, true, true, false),
            listOf("08:59", "09:00", "17:59", "18:00").map { window("09:00", "18:00", it) },
        )
    }

    @Test
    fun `time window may cross midnight`() {
        assertEquals(
            listOf(true, true, true, false, false),
            listOf("22:00", "23:30", "03:00", "08:00", "12:00").map { window("22:00", "08:00", it) },
        )
    }

    @Test
    fun `time window with the same start and end is the whole day`() {
        assertEquals(true, window("07:00", "07:00", "03:00"))
    }

    @Test
    fun `weekdays condition passes only on the chosen days`() {
        // Mon..Fri = 1..5; 2026-11-26 is Thursday, 2026-11-28 is Saturday.
        assertEquals(true, days("1,2,3,4,5", "2026-11-26"))
        assertEquals(false, days("1,2,3,4,5", "2026-11-28"))
        assertEquals(true, days("6,7", "2026-11-28"))
    }

    @Test
    fun `condition with unreadable params is not met`() {
        assertEquals(false, window("25:00", "08:00", "23:00"))
        assertEquals(false, days("", "2026-11-26"))
    }
}
