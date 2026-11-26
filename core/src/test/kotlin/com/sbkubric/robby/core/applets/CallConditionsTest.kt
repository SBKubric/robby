package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applet.ServerPhoneState
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CallConditionsTest {

    private val time = ZonedDateTime.parse("2026-11-26T12:00:00+03:00[Europe/Moscow]")

    private fun isActive(call: CallState?) =
        BuiltInApplets.catalog.condition(CallApplet.ACTIVE)!!.isMet(emptyMap(), ServerPhoneState(time, call = call))

    @Test
    fun `call is going on while one rings, waits or is talked on, not when idle or unknown`() {
        assertEquals(
            listOf(true, true, false, false),
            listOf(CallState.RINGING, CallState.OFFHOOK, CallState.IDLE, null).map(::isActive),
        )
    }
}
