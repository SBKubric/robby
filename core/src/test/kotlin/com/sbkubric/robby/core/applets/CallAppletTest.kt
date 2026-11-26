package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.texts
import com.sbkubric.robby.core.FakeCallWatchStore
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.engine.ContactNames
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CallAppletTest {

    private val now = Instant.parse("2026-11-26T11:02:00Z")
    private val sent = mutableListOf<String>()

    private fun scenario(trigger: String, caller: String = "", text: String = "{call.number} {call.contact}") = Scenario(
        id = "$trigger $caller",
        name = "Звонок",
        enabled = true,
        trigger = Trigger(trigger, mapOf(CallApplet.P_CALLER to caller)),
        actions = listOf(Action("telegram.send", mapOf("text" to text))),
    )

    private fun engine(vararg scenarios: Scenario) = HandleEvent(
        scenarios = FakeScenarioRepository(*scenarios),
        catalog = BuiltInApplets.catalog,
        log = FakeActivityLog(),
        deviceName = { "Pixel дома" },
        battery = { null },
        call = { null },
        clock = Clock.fixed(now, ZoneId.of("Europe/Moscow")),
    )

    private fun missed(number: String, contact: String = "") =
        Event(CallApplet.MISSED, mapOf(CallApplet.NUMBER to number, CallApplet.CONTACT to contact), now)

    @Test
    fun `caller filter matches the number in any formatting or the contact name ignoring case, empty matches any`() = runBlocking {
        val engine = engine(
            scenario(CallApplet.MISSED, "+7 (916) 123-45-67"),
            scenario(CallApplet.MISSED, "мама"),
            scenario(CallApplet.MISSED, ""),
            scenario(CallApplet.INCOMING, ""),
        )

        assertEquals(3, engine(missed("+79161234567", "Мама")).size)
        assertEquals(2, engine(missed("+79161234567")).size)
        assertEquals(2, engine(missed("+79035550000", "Мама")).size)
        assertEquals(1, engine(missed("+79035550000")).size)
    }

    @Test
    fun `missed calls from a known and an unknown number reach Telegram with the number and the contact`() = runBlocking {
        val contacts = ContactNames { mapOf("+79161234567" to "Мама")[it] }
        val watch = CallWatch(FakeCallWatchStore(), contacts)
        val engine = engine(scenario(CallApplet.MISSED, text = "Пропущенный от {call.number} ({call.contact})"))

        for (number in listOf("+79161234567", "+79035550000")) {
            listOf(CallState.RINGING to null, CallState.RINGING to number, CallState.IDLE to null, CallState.IDLE to null)
                .forEach { (state, n) -> watch.onReading(state, n, now, numberExpected = true).forEach { sent += engine(it).flatMap { run -> run.texts() } } }
        }

        assertEquals(listOf("Пропущенный от +79161234567 (Мама)", "Пропущенный от +79035550000 (—)"), sent)
    }
}
