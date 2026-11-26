package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HandleEventTest {

    private val now = Instant.parse("2026-11-26T11:02:00Z")
    private val log = FakeActivityLog()
    private var battery: BatteryState? = BatteryState(level = 64, temperature = 31.5, plugged = true)
    private var call: CallState? = CallState.IDLE
    private val sent = mutableListOf<String>()
    private val telegram = ActionPerformer { params ->
        sent += params.getValue("text")
        ActionResult.Done("Доставлено в «Дом»")
    }

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

    private fun engine(vararg scenarios: Scenario, performers: Map<String, ActionPerformer> = mapOf("telegram.send" to telegram)) =
        HandleEvent(
            scenarios = FakeScenarioRepository(*scenarios),
            catalog = BuiltInApplets.catalog,
            performers = performers,
            log = log,
            deviceName = { "Pixel дома" },
            battery = { battery },
            call = { call },
            clock = Clock.fixed(now, ZoneId.of("Europe/Moscow")),
        )

    private fun sms(sender: String, text: String = "Код 1234") =
        Event("sms.in", mapOf("sms.sender" to sender, "sms.text" to text), now)

    @Test
    fun `SMS from the configured sender is sent to Telegram and logged`() = runBlocking {
        engine(smsScenario()).invoke(sms("900"))

        assertEquals(listOf("SMS от 900: Код 1234"), sent)
        assertEquals(listOf(Run("bank", "SMS от банка", now, RunStatus.DONE, "Доставлено в «Дом»")), log.runs.value)
    }

    @Test
    fun `SMS from another sender is filtered out and not logged`() = runBlocking {
        val runs = engine(smsScenario()).invoke(sms("+79161234567"))

        assertEquals(emptyList(), runs)
        assertEquals(emptyList<String>(), sent)
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
        assertEquals(emptyList<String>(), sent)
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

        assertEquals(listOf("Pixel дома, 26.11.2026 14:02: [—]"), sent)
    }

    @Test
    fun `actions run in order and a failure stops the run`() = runBlocking {
        val calls = mutableListOf<String>()
        val performers = mapOf(
            "telegram.send" to ActionPerformer { params ->
                calls += params.getValue("text")
                if (params["text"] == "второе") ActionResult.Failed("Нет сети") else ActionResult.Done()
            },
        )
        val scenario = smsScenario().copy(
            actions = listOf("первое", "второе", "третье").map { Action("telegram.send", mapOf("text" to it)) },
        )

        engine(scenario, performers = performers).invoke(sms("900"))

        assertEquals(listOf("первое", "второе"), calls)
        assertEquals(listOf(RunStatus.FAILED to "Нет сети"), log.runs.value.map { it.status to it.message })
    }

    @Test
    fun `action without a performer fails the run`() = runBlocking {
        engine(smsScenario(), performers = emptyMap()).invoke(sms("900"))

        assertEquals(
            listOf(Run("bank", "SMS от банка", now, RunStatus.FAILED, "telegram.send", RunReason.NO_PERFORMER)),
            log.runs.value,
        )
    }

    @Test
    fun `run that does not finish within the time limit is logged as timed out`() = runBlocking {
        val performers = mapOf(
            "telegram.send" to ActionPerformer { params ->
                if (params["text"] == "slow") delay(10_000)
                ActionResult.Done("Доставлено")
            },
        )
        val started = System.nanoTime()

        val runs = engine(smsScenario(id = "slow", message = "slow"), smsScenario(id = "fast", message = "fast"), performers = performers)
            .invoke(sms("900"), timeLimit = 200.milliseconds)

        assertTrue(System.nanoTime() - started < 5_000_000_000, "the engine must not wait for the slow action")
        assertEquals(
            mapOf(
                "slow" to Triple(RunStatus.FAILED, null, RunReason.TIMED_OUT),
                "fast" to Triple(RunStatus.DONE, "Доставлено", null),
            ),
            log.runs.value.associate { it.scenarioId to Triple(it.status, it.message, it.reason) },
        )
        assertEquals(runs.toSet(), log.runs.value.toSet())
    }

    @Test
    fun `performer that throws fails only its own run`() = runBlocking {
        val performers = mapOf(
            "telegram.send" to ActionPerformer { params ->
                if (params["text"] == "boom") error("Сбой адаптера") else ActionResult.Done()
            },
        )
        engine(smsScenario(id = "a", message = "boom"), smsScenario(id = "b", message = "ok"), performers = performers)
            .invoke(sms("900"))

        assertEquals(
            mapOf("a" to RunStatus.FAILED, "b" to RunStatus.DONE),
            log.runs.value.associate { it.scenarioId to it.status },
        )
        assertEquals("Сбой адаптера", log.runs.value.single { it.scenarioId == "a" }.message)
    }

    @Test
    fun `log keeps 30 days and at most 2000 runs`() = runBlocking {
        val old = Run("x", "Старый", now.minus(java.time.Duration.ofDays(31)), RunStatus.DONE, null)
        val recent = List(2000) { Run("x", "Недавний", now.minusSeconds(60L + it), RunStatus.DONE, null) }
        log.runs.value = recent + old

        engine(smsScenario()).invoke(sms("900"))

        assertEquals(2000, log.runs.value.size)
        assertEquals("SMS от банка", log.runs.value.first().scenarioName)
        assertEquals(false, log.runs.value.any { it.scenarioName == "Старый" })
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

        assertEquals(listOf("SMS от 900: Код 1234"), sent)
        assertEquals(listOf(RunStatus.DONE), log.runs.value.map { it.status })
    }

    @Test
    fun `condition not met skips the run and the log says which condition`() = runBlocking {
        val runs = engine(smsScenario().copy(conditions = listOf(thursday, night))).invoke(sms("900"))

        assertEquals(emptyList<String>(), sent)
        val skipped = Run("bank", "SMS от банка", now, RunStatus.SKIPPED, "system.window", RunReason.CONDITION_NOT_MET)
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

        assertEquals(emptyList<String>(), sent)
        assertEquals(
            Run("bank", "SMS от банка", now, RunStatus.SKIPPED, null, RunReason.COOLDOWN),
            log.runs.value.first(),
        )
    }

    @Test
    fun `event after the pause has passed runs`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 15, RunStatus.DONE))

        engine(smsScenario().copy(cooldownMinutes = 15)).invoke(sms("900"))

        assertEquals(1, sent.size)
    }

    @Test
    fun `pause counts from the last executed run, failed ones too, but not from skipped ones`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 5, RunStatus.SKIPPED), ran(minutesAgo = 20, RunStatus.DONE))
        engine(smsScenario().copy(cooldownMinutes = 15)).invoke(sms("900"))
        assertEquals(1, sent.size)

        log.runs.value = listOf(ran(minutesAgo = 5, RunStatus.FAILED))
        engine(smsScenario().copy(cooldownMinutes = 15)).invoke(sms("900"))
        assertEquals(RunReason.COOLDOWN, log.runs.value.first().reason)
    }

    @Test
    fun `pause is per scenario and absent by default`() = runBlocking {
        log.runs.value = listOf(ran(minutesAgo = 1, RunStatus.DONE, scenarioId = "other"), ran(minutesAgo = 1, RunStatus.DONE, scenarioId = "free"))

        engine(smsScenario(id = "bank").copy(cooldownMinutes = 15), smsScenario(id = "free")).invoke(sms("900"))

        assertEquals(2, sent.size)
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

        assertEquals(listOf("Pixel дома включился"), sent)
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
        assertEquals(listOf("Заряд 19 %, 30,5 °C"), sent)
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

        assertEquals(listOf("battery" to RunStatus.SKIPPED, "time" to RunStatus.DONE), runs.map { it.scenarioId to it.status })
    }

    @Test
    fun `call condition sees the call state at the time of the event`() = runBlocking {
        val engine = engine(smsScenario().copy(conditions = listOf(Condition("call.active"))))

        call = CallState.OFFHOOK
        val during = engine(sms("900"))
        call = CallState.IDLE
        val after = engine(sms("900"))

        assertEquals(listOf(RunStatus.DONE, RunStatus.SKIPPED), (during + after).map { it.status })
    }
}
