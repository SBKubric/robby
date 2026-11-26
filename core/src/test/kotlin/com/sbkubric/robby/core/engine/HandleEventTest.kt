package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunAction
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import com.sbkubric.robby.core.texts
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deciding: trigger → filters → conditions → pause, and recording the run to deliver. Delivery is [DeliverRunsTest]. */
class HandleEventTest {

    private val now = Instant.parse("2026-11-26T11:02:00Z")
    private val log = FakeActivityLog()
    private var battery: BatteryState? = BatteryState(level = 64, temperature = 31.5, plugged = true)
    private var call: CallState? = CallState.IDLE

    private fun smsScenario(
        id: String = "bank",
        sender: String = "900",
        text: String = "",
        enabled: Boolean = true,
        message: String = "SMS от {sms.sender}: {sms.text}",
    ) = Scenario(
        id = id,
        name = "SMS от банка",
        enabled = enabled,
        trigger = Trigger("sms.in", mapOf("sender" to sender, "text" to text)),
        actions = listOf(Action("telegram.send", mapOf("text" to message))),
    )

    private fun engine(vararg scenarios: Scenario) = HandleEvent(
        scenarios = FakeScenarioRepository(*scenarios),
        catalog = BuiltInApplets.catalog,
        log = log,
        deviceName = { "Pixel дома" },
        battery = { battery },
        call = { call },
        clock = Clock.fixed(now, ZoneId.of("Europe/Moscow")),
    )

    private fun sms(sender: String, text: String = "Код 1234") =
        Event("sms.in", mapOf("sms.sender" to sender, "sms.text" to text), now)

    /** The texts the recorded runs will send, newest run first. */
    private fun recorded() = log.runs.value.flatMap { it.texts() }

    @Test
    fun `matching SMS is recorded at once as pending with the message already rendered`() = runBlocking {
        val runs = engine(smsScenario()).invoke(sms("900"))

        val pending = Run(
            scenarioId = "bank",
            scenarioName = "SMS от банка",
            time = now,
            status = RunStatus.PENDING,
            message = null,
            id = 1,
            actions = listOf(RunAction("telegram.send", mapOf("text" to "SMS от 900: Код 1234"))),
        )
        assertEquals(listOf(pending), runs)
        assertEquals(listOf(pending), log.runs.value)
    }

    @Test
    fun `snapshot keeps every action in order, rendering only template params`() = runBlocking {
        val scenario = smsScenario().copy(
            actions = listOf(
                Action("system.notify", mapOf("title" to "SMS {sms.sender}", "text" to "{sms.text}")),
                Action("telegram.send", mapOf("text" to "{device}: {sms.text}")),
            ),
        )

        val run = engine(scenario).invoke(sms("900")).single()

        assertEquals(
            listOf(
                RunAction("system.notify", mapOf("title" to "SMS 900", "text" to "Код 1234")),
                RunAction("telegram.send", mapOf("text" to "Pixel дома: Код 1234")),
            ),
            run.actions,
        )
    }

    @Test
    fun `SMS from another sender is filtered out and not logged`() = runBlocking {
        val runs = engine(smsScenario()).invoke(sms("+79161234567"))

        assertEquals(emptyList(), runs)
        assertEquals(emptyList(), log.runs.value)
    }

    @Test
    fun `sender filter ignores case and phone number formatting`() = runBlocking {
        val engine = engine(smsScenario(id = "a", sender = "+7 (916) 123-45-67"), smsScenario(id = "b", sender = "sberbank"))

        assertEquals(listOf("a"), engine(sms("+79161234567")).map { it.scenarioId })
        assertEquals(listOf("b"), engine(sms("SberBank")).map { it.scenarioId })
    }

    @Test
    fun `text filter passes SMS containing the text, ignoring case`() = runBlocking {
        val engine = engine(smsScenario(sender = "", text = "код"))

        assertEquals(1, engine(sms("900", "Ваш КОД: 1234")).size)
        assertEquals(0, engine(sms("900", "Баланс 100 ₽")).size)
    }

    @Test
    fun `empty filters let any SMS through`() = runBlocking {
        assertEquals(1, engine(smsScenario(sender = " ", text = "")).invoke(sms("anyone", "")).size)
    }

    @Test
    fun `disabled scenarios and scenarios with another trigger do not run`() = runBlocking {
        val other = smsScenario(id = "other").copy(trigger = Trigger("call.missed"))
        val runs = engine(smsScenario(id = "off", enabled = false), other).invoke(sms("900"))

        assertEquals(emptyList(), runs)
        assertEquals(emptyList(), log.runs.value)
    }

    @Test
    fun `every matching scenario gets its own run`() = runBlocking {
        val runs = engine(smsScenario(id = "a"), smsScenario(id = "b", sender = "")).invoke(sms("900"))

        assertEquals(setOf("a", "b"), runs.map { it.scenarioId }.toSet())
        assertEquals(2, log.runs.value.size)
    }

    @Test
    fun `common variables come from the event time and the device, empty values become a dash`() = runBlocking {
        engine(smsScenario(message = "{device}, {now}: [{sms.text}]")).invoke(sms("900", text = ""))

        assertEquals(listOf("Pixel дома, 26.11.2026 14:02: [—]"), recorded())
    }

    @Test
    fun `log keeps 30 days and at most 2000 runs, but never drops a pending one`() = runBlocking {
        val old = Run("x", "Старый", now.minus(java.time.Duration.ofDays(31)), RunStatus.DONE, null, id = 9001)
        val waiting = Run("x", "Ждёт", now.minusSeconds(3000), RunStatus.PENDING, null, id = 9002)
        val recent = List(2000) { Run("x", "Недавний", now.minusSeconds(60L + it), RunStatus.DONE, null, id = 10_000L + it) }
        log.runs.value = recent + waiting + old

        engine(smsScenario()).invoke(sms("900"))

        assertEquals(2001, log.runs.value.size)
        assertEquals("SMS от банка", log.runs.value.first().scenarioName)
        assertEquals(false, log.runs.value.any { it.scenarioName == "Старый" })
        assertEquals(true, log.runs.value.any { it.scenarioName == "Ждёт" })
    }

    // The fixed clock is Thursday 2026-11-26, 14:02 in Moscow.
    private val thursday = Condition("system.days", mapOf("days" to "4"))
    private val night = Condition("system.window", mapOf("from" to "22:00", "to" to "08:00"))

    private fun ran(minutesAgo: Long, status: RunStatus, scenarioId: String = "bank") =
        Run(scenarioId, "SMS от банка", now.minusSeconds(minutesAgo * 60), status, null)

    @Test
    fun `scenario runs when all its conditions are met`() = runBlocking {
        engine(smsScenario().copy(conditions = listOf(thursday, Condition("system.window", mapOf("from" to "09:00", "to" to "18:00")))))
            .invoke(sms("900"))

        assertEquals(listOf("SMS от 900: Код 1234"), recorded())
        assertEquals(listOf(RunStatus.PENDING), log.runs.value.map { it.status })
    }

    @Test
    fun `condition not met skips the run and the log says which condition`() = runBlocking {
        val runs = engine(smsScenario().copy(conditions = listOf(thursday, night))).invoke(sms("900"))

        val skipped = Run("bank", "SMS от банка", now, RunStatus.SKIPPED, "system.window", RunReason.CONDITION_NOT_MET, id = 1)
        assertEquals(listOf(skipped), runs)
        assertEquals(listOf(skipped), log.runs.value)
    }

    @Test
    fun `condition missing from the catalog is not met`() = runBlocking {
        engine(smsScenario().copy(conditions = listOf(Condition("battery.level")))).invoke(sms("900"))

        assertEquals(listOf("battery.level" to RunReason.CONDITION_NOT_MET), log.runs.value.map { it.message to it.reason })
    }

    @Test
    fun `event within the pause after the last run is skipped as a pause`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 10, RunStatus.DONE))

        engine(smsScenario().copy(cooldownMinutes = 15)).invoke(sms("900"))

        assertEquals(
            Run("bank", "SMS от банка", now, RunStatus.SKIPPED, null, RunReason.COOLDOWN, id = 1),
            log.runs.value.first(),
        )
    }

    @Test
    fun `event after the pause has passed runs`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 15, RunStatus.DONE))

        engine(smsScenario().copy(cooldownMinutes = 15)).invoke(sms("900"))

        assertEquals(RunStatus.PENDING, log.runs.value.first().status)
    }

    @Test
    fun `pause counts from the last executed run, pending and failed ones too, not from skipped or cancelled ones`() = runBlocking {
        val engine = engine(smsScenario().copy(cooldownMinutes = 15))

        log.runs.value = listOf(ran(minutesAgo = 5, RunStatus.SKIPPED), ran(minutesAgo = 6, RunStatus.CANCELLED), ran(minutesAgo = 20, RunStatus.DONE))
        assertEquals(RunStatus.PENDING, engine(sms("900")).single().status)

        listOf(RunStatus.FAILED, RunStatus.PENDING).forEach { status ->
            log.runs.value = listOf(ran(minutesAgo = 5, status))
            assertEquals(RunReason.COOLDOWN, engine(sms("900")).single().reason, "after a $status run")
        }
    }

    @Test
    fun `two events at once cannot both pass the pause`() = runBlocking {
        val engine = engine(smsScenario().copy(cooldownMinutes = 15))

        val runs = listOf(async { engine(sms("900")) }, async { engine(sms("900")) }).awaitAll().flatten()

        assertEquals(listOf(RunStatus.PENDING, RunStatus.SKIPPED), runs.map { it.status }.sortedBy { it.ordinal })
    }

    @Test
    fun `pause is per scenario and absent by default`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 1, RunStatus.DONE, scenarioId = "other"), ran(minutesAgo = 1, RunStatus.DONE, scenarioId = "free"))

        val runs = engine(smsScenario(id = "bank").copy(cooldownMinutes = 15), smsScenario(id = "free")).invoke(sms("900"))

        assertEquals(listOf(RunStatus.PENDING, RunStatus.PENDING), runs.map { it.status })
    }

    @Test
    fun `conditions are checked before the pause`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 5, RunStatus.DONE))

        engine(smsScenario().copy(conditions = listOf(night), cooldownMinutes = 15)).invoke(sms("900"))

        assertEquals(RunReason.CONDITION_NOT_MET, log.runs.value.first().reason)
    }

    @Test
    fun `server phone start runs boot scenarios`() = runBlocking {
        val boot = smsScenario(message = "{device} включился").copy(trigger = Trigger("system.boot"))

        engine(boot).invoke(Event("system.boot", emptyMap(), now))

        assertEquals(listOf("Pixel дома включился"), recorded())
    }

    private fun batteryScenario(id: String, trigger: Trigger, vararg conditions: Condition) = smsScenario(
        id = id,
        message = "Заряд {battery.level} %, {battery.temperature} °C",
    ).copy(trigger = trigger, conditions = conditions.toList())

    @Test
    fun `battery event runs only the scenarios with the threshold it crossed`() = runBlocking {
        val runs = engine(
            batteryScenario("20", Trigger("battery.below", mapOf("level" to "20"))),
            batteryScenario("50", Trigger("battery.below", mapOf("level" to "50"))),
        ).invoke(Event("battery.below", mapOf("battery.level" to "19", "battery.temperature" to "30,5", "battery.threshold" to "20"), now))

        assertEquals(listOf("20"), runs.map { it.scenarioId })
        assertEquals(listOf("Заряд 19 %, 30,5 °C"), recorded())
    }

    @Test
    fun `battery conditions see the battery at the time of the event`() = runBlocking {
        val engine = engine(
            smsScenario(id = "plugged").copy(conditions = listOf(Condition("battery.charging"))),
            smsScenario(id = "charged").copy(conditions = listOf(Condition("battery.level_above", mapOf("level" to "50")))),
        )

        battery = BatteryState(level = 40, temperature = 30.0, plugged = false)
        val runs = engine(sms("900"))

        assertEquals(
            listOf("plugged" to "battery.charging", "charged" to "battery.level_above"),
            runs.map { it.scenarioId to it.message },
        )
        assertEquals(setOf(RunStatus.SKIPPED), runs.map { it.status }.toSet())
    }

    @Test
    fun `battery conditions are not met when the battery is unknown, other conditions still work`() = runBlocking {
        battery = null
        val runs = engine(
            smsScenario(id = "battery").copy(conditions = listOf(Condition("battery.not_charging"))),
            smsScenario(id = "time").copy(conditions = listOf(thursday)),
        ).invoke(sms("900"))

        assertEquals(listOf("battery" to RunStatus.SKIPPED, "time" to RunStatus.PENDING), runs.map { it.scenarioId to it.status })
    }

    @Test
    fun `call condition sees the call state at the time of the event`() = runBlocking {
        val engine = engine(smsScenario().copy(conditions = listOf(Condition("call.active"))))

        call = CallState.OFFHOOK
        val during = engine(sms("900"))
        call = CallState.IDLE
        val after = engine(sms("900"))

        assertEquals(listOf(RunStatus.PENDING, RunStatus.SKIPPED), (during + after).map { it.status })
    }
}
