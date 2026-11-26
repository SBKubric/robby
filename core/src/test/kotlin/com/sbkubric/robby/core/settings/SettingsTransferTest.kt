package com.sbkubric.robby.core.settings

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeConnectorHealth
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.MutableClock
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.engine.ActionPerformer
import com.sbkubric.robby.core.engine.ActionResult
import com.sbkubric.robby.core.engine.DeliverRuns
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.engine.TestScenario
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioDraft
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioValidator
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Export from one server phone, import on another: «импорт заменяет всё», nothing secret travels. */
class SettingsTransferTest {

    private val catalog = BuiltInApplets.catalog
    private val clock = MutableClock(Instant.parse("2026-11-26T11:02:00Z"))

    /** One server phone: its scenarios, name, activity log and connectors. */
    private inner class Phone(name: String?, vararg scenarios: Scenario) {
        val repository = FakeScenarioRepository(*scenarios)
        val phoneName = FakePhoneName(name)
        val log = FakeActivityLog()
        val reset = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val delivery = DeliverRuns(
            log,
            repository,
            catalog,
            mapOf("telegram.send" to ActionPerformer { params, _ -> sent += params.getValue("text"); ActionResult.Done() }),
            FakeConnectorHealth(),
            clock,
        )
        val export = ExportSettings(repository, phoneName)
        val import = ImportSettings(repository, phoneName, ScenarioValidator(catalog), catalog, delivery) { reset += it }
        val events = HandleEvent(repository, catalog, log, { "Pixel" }, { null }, { null }, clock)

        /** Reads the file and, if it can be read, replaces everything with it, as the user confirms. */
        suspend fun importConfirmed(file: String): ImportSettings.Plan {
            val plan = assertIs<ImportSettings.Read.Ready>(import.read(file)).plan
            import.apply(plan)
            return plan
        }
    }

    private class FakePhoneName(var name: String?) : ServerPhoneNameSetting {
        override fun customName() = name
        override fun setCustomName(name: String?) { this.name = name }
    }

    private val bank = Scenario(
        id = "bank",
        name = "SMS от банка",
        trigger = Trigger("sms.in", mapOf("sender" to "900")),
        conditions = listOf(Condition("system.window", mapOf("from" to "08:00", "to" to "23:00"))),
        actions = listOf(Action("telegram.send", mapOf("text" to "SMS: {sms.text}"))),
        cooldownMinutes = 15,
    )
    private val heartbeat = Scenario(
        id = "pulse",
        name = "Пульс",
        enabled = false,
        trigger = Trigger("system.schedule", mapOf("days" to "1,2,3,4,5,6,7", "time" to "09:00")),
        actions = listOf(Action("telegram.send", mapOf("text" to "{device} на связи"))),
    )

    /** From a later Robby, or one with an applet this build lacks. */
    private val bridge = Scenario(
        id = "bridge",
        name = "Мост звонка",
        trigger = Trigger("call.incoming"),
        actions = listOf(Action("module.bridge")),
    )

    @Test
    fun `export from one phone imports on another as the same scenarios and server phone name`() = runBlocking {
        val old = Phone("Pixel дома", bank, heartbeat)
        val new = Phone(null, Scenario("other", "Старый", trigger = Trigger("system.boot"), actions = listOf(Action("telegram.send", mapOf("text" to "x")))))

        val plan = new.importConfirmed(old.export())

        assertEquals(setOf(bank, heartbeat), new.repository.all().toSet(), "everything replaced, the old scenario gone")
        assertEquals("Pixel дома", new.phoneName.name)
        assertEquals(ImportSettings.Plan(listOf(bank, heartbeat), "Pixel дома", disabled = emptyList(), connectors = listOf("Telegram")), plan)
    }

    @Test
    fun `phone that used its own name gets back its own name`() = runBlocking {
        val new = Phone("Старое имя")

        new.importConfirmed(Phone(null, bank).export())

        assertEquals(null, new.phoneName.name)
    }

    @Test
    fun `export file holds the scenarios and the phone's name, no connector account`() = runBlocking {
        val file = Phone("Pixel дома", bank).export()

        assertEquals(setOf("format", "schemaVersion", "serverPhoneName", "scenarios"), Json.parseToJsonElement(file).jsonObject.keys)
    }

    @Test
    fun `scenario with an element this build lacks is imported switched off, and the plan says why`() = runBlocking {
        val new = Phone(null)
        val file = SettingsFormat.encode(SettingsExport(listOf(bank, bridge), null))

        val plan = new.importConfirmed(file)

        assertEquals(listOf(bank, bridge.copy(enabled = false)), new.repository.all())
        assertEquals(
            listOf(ImportSettings.DisabledScenario("Мост звонка", listOf(ScenarioProblem.UnknownElements(setOf("module.bridge"))))),
            plan.disabled,
        )
    }

    @Test
    fun `scenario that fails validation in any other way is imported switched off too`() = runBlocking {
        val new = Phone(null)
        val broken = bank.copy(id = "b2", actions = listOf(Action("telegram.send", mapOf("text" to "{call.number}"))))

        val plan = new.importConfirmed(SettingsFormat.encode(SettingsExport(listOf(broken), null)))

        assertEquals(false, new.repository.get("b2")?.enabled)
        assertEquals(listOf(ScenarioProblem.UnavailableVariables(setOf("call.number"))), plan.disabled.single().problems)
    }

    @Test
    fun `import resets every connector, so it is not configured`() = runBlocking {
        val new = Phone(null)

        new.importConfirmed(Phone(null, bank).export())

        assertEquals(listOf("telegram"), new.reset)
    }

    @Test
    fun `pending runs of the replaced scenarios are cancelled, test runs too, and never delivered`() = runBlocking {
        val new = Phone(null, bank.copy(conditions = emptyList()))
        val waiting = new.events(Event("sms.in", mapOf("sms.sender" to "900", "sms.text" to "старое"), clock.now)).single()
        val test = TestScenario(ScenarioValidator(catalog), catalog, new.log, { "Pixel" }, clock)
        val tested = assertIs<TestScenario.Result.Recorded>(test(ScenarioDraft.of(bank))).run

        new.importConfirmed(Phone(null, bank).export())
        new.delivery()

        assertEquals(
            listOf(RunStatus.CANCELLED to RunReason.SETTINGS_IMPORTED, RunStatus.CANCELLED to RunReason.SETTINGS_IMPORTED),
            listOf(waiting, tested).map { run -> new.log.get(run.id).let { it.status to it.reason } },
        )
        assertEquals(emptyList(), new.sent, "the same scenario id in the file does not revive them")
    }

    @Test
    fun `file from a newer Robby is refused and nothing changes`() = runBlocking {
        val new = Phone("Pixel", heartbeat)
        val newer = Phone(null, bank).export().replace("\"schemaVersion\":1,", "\"schemaVersion\":2,")

        assertEquals(ImportSettings.Read.NewerVersion, new.import.read(newer))
        assertEquals(ImportSettings.Read.NotSettings, new.import.read("{}"))
        assertEquals(listOf(heartbeat), new.repository.all())
        assertEquals("Pixel", new.phoneName.name)
        assertEquals(emptyList(), new.reset)
    }
}
