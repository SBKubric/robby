package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeConnectorHealth
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.MutableClock
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioDraft
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioValidator
import com.sbkubric.robby.core.scenario.Trigger
import com.sbkubric.robby.core.texts
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** «Тест» in the editor: one run of the draft with sample values, past conditions and the pause, then delivered as usual. */
class TestScenarioTest {

    // Thursday 2026-11-26, 14:02 in Moscow.
    private val now = Instant.parse("2026-11-26T11:02:00Z")
    private val clock = MutableClock(now)
    private val log = FakeActivityLog()
    private val repository = FakeScenarioRepository()
    private val catalog = BuiltInApplets.catalog

    private val test = TestScenario(ScenarioValidator(catalog), catalog, log, deviceName = { "Pixel дома" }, clock = clock)

    private val sent = mutableListOf<String>()
    private val deliver = DeliverRuns(
        log,
        repository,
        catalog,
        mapOf("telegram.send" to ActionPerformer { params, _ -> sent += params.getValue("text"); ActionResult.Done() }),
        FakeConnectorHealth(),
        clock,
    )

    private val engine = HandleEvent(repository, catalog, log, { "Pixel дома" }, { null }, { null }, clock)

    /** Only at night, with a pause of an hour: a real SMS now would be skipped. */
    private val draft = ScenarioDraft(
        id = "bank",
        name = "SMS от банка",
        trigger = Trigger("sms.in", mapOf("sender" to "900")),
        conditions = listOf(Condition("system.window", mapOf("from" to "22:00", "to" to "08:00"))),
        actions = listOf(Action("telegram.send", mapOf("text" to "{device} {now}: SMS от {sms.sender}: {sms.text}"))),
        cooldownMinutes = 60,
    )

    @Test
    fun `test records a pending test run with the trigger's sample values and the real time and device`() = runBlocking {
        val result = test(draft)

        val run = assertIs<TestScenario.Result.Recorded>(result).run
        assertEquals(
            listOf(RunStatus.PENDING, true, "bank", "SMS от банка"),
            listOf(run.status, run.test, run.scenarioId, run.scenarioName),
        )
        assertEquals(listOf("Pixel дома 26.11.2026 14:02: SMS от 900: Код 1234. Никому не сообщайте его"), run.texts())
        assertEquals(listOf(run), log.runs.value)
    }

    @Test
    fun `test goes past unmet conditions and the pause, and delivery performs it`() = runBlocking {
        repository.save(scenario(draft))
        val real = engine(Event("sms.in", mapOf("sms.sender" to "900", "sms.text" to "x"), now)).single()
        assertEquals(RunStatus.SKIPPED, real.status, "outside the night window")

        test(draft)
        test(draft)
        deliver()

        assertEquals(2, sent.size, "neither the window nor the hour's pause holds a test back")
    }

    @Test
    fun `test run does not count for the pause`() = runBlocking {
        val always = draft.copy(conditions = emptyList())
        repository.save(scenario(always))
        test(always)

        val real = engine(Event("sms.in", mapOf("sms.sender" to "900", "sms.text" to "x"), now.plusSeconds(60))).single()

        assertEquals(RunStatus.PENDING, real.status)
    }

    @Test
    fun `unsaved changes are tested, and the test run is delivered though the draft is not stored or is switched off`() = runBlocking {
        repository.save(scenario(draft).copy(enabled = false))
        test(draft.copy(enabled = false, actions = listOf(Action("telegram.send", mapOf("text" to "новый текст")))))
        test(draft.copy(id = null, name = "Новый", actions = listOf(Action("telegram.send", mapOf("text" to "без сохранения")))))

        deliver()

        assertEquals(listOf("новый текст", "без сохранения"), sent)
        assertEquals(listOf(RunStatus.DONE, RunStatus.DONE), log.runs.value.map { it.status })
    }

    @Test
    fun `invalid draft is not tested`() = runBlocking {
        val result = test(draft.copy(actions = listOf(Action("telegram.send", mapOf("text" to "{call.number}")))))

        assertEquals(
            TestScenario.Result.Invalid(listOf(ScenarioProblem.UnavailableVariables(setOf("call.number")))),
            result,
        )
        assertEquals(emptyList(), log.runs.value)
    }

    @Test
    fun `trigger without variables of its own is tested with the common ones`() = runBlocking {
        val heartbeat = ScenarioDraft(
            name = "Пульс",
            trigger = Trigger("system.schedule", mapOf("days" to "1,2,3,4,5,6,7", "time" to "09:00")),
            actions = listOf(Action("telegram.send", mapOf("text" to "{device} на связи"))),
        )

        val run = assertIs<TestScenario.Result.Recorded>(test(heartbeat)).run

        assertEquals(listOf("Pixel дома на связи"), run.texts())
    }

    private fun scenario(draft: ScenarioDraft) = Scenario(
        id = draft.id!!,
        name = draft.name,
        enabled = draft.enabled,
        trigger = draft.trigger!!,
        conditions = draft.conditions,
        actions = draft.actions,
        cooldownMinutes = draft.cooldownMinutes,
    )
}
