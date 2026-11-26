package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.ServerPhoneState
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryConditionsTest {

    private val catalog = BuiltInApplets.catalog
    private val time = ZonedDateTime.parse("2026-11-26T12:00:00+03:00[Europe/Moscow]")

    private fun isMet(condition: String, battery: BatteryState?, vararg params: Pair<String, String>) =
        catalog.condition(condition)!!.isMet(params.toMap(), ServerPhoneState(time, battery))

    private fun battery(level: Int, plugged: Boolean = true) = BatteryState(level, 30.0, plugged)

    @Test
    fun `level above passes only when the charge is greater than the threshold`() {
        assertEquals(
            listOf(false, false, true),
            listOf(49, 50, 51).map { isMet("battery.level_above", battery(it), "level" to "50") },
        )
    }

    @Test
    fun `on charger and not on charger follow the plug, also when full`() {
        assertEquals(listOf(true, false), listOf(true, false).map { isMet("battery.charging", battery(100, plugged = it)) })
        assertEquals(listOf(false, true), listOf(true, false).map { isMet("battery.not_charging", battery(100, plugged = it)) })
    }

    @Test
    fun `battery conditions are not met when the phone did not report the battery or the threshold is unreadable`() {
        assertEquals(false, isMet("battery.level_above", null, "level" to "50"))
        assertEquals(false, isMet("battery.charging", null))
        assertEquals(false, isMet("battery.not_charging", null))
        assertEquals(false, isMet("battery.level_above", battery(80), "level" to ""))
    }
}
