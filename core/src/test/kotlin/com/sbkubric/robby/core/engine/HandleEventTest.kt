package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunError
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
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
            listOf(Run("bank", "SMS от банка", now, RunStatus.FAILED, "telegram.send", RunError.NO_PERFORMER)),
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
                "slow" to Triple(RunStatus.FAILED, null, RunError.TIMED_OUT),
                "fast" to Triple(RunStatus.DONE, "Доставлено", null),
            ),
            log.runs.value.associate { it.scenarioId to Triple(it.status, it.message, it.error) },
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
}
