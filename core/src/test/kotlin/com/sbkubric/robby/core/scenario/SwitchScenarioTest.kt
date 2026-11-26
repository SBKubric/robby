package com.sbkubric.robby.core.scenario

import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applets.BuiltInApplets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchScenarioTest {

    private val valid = Scenario(
        id = "bank",
        name = "SMS от банка",
        enabled = false,
        trigger = Trigger("sms.in"),
        actions = listOf(Action("telegram.send", mapOf("text" to "{sms.text}"))),
    )

    /** Imported from a phone with an applet this build lacks. */
    private val unavailable = Scenario(
        id = "bridge",
        name = "Мост звонка",
        enabled = false,
        trigger = Trigger("call.incoming"),
        actions = listOf(Action("module.bridge")),
    )

    private val repository = FakeScenarioRepository(valid, unavailable)
    private val switch = SwitchScenario(repository, ScenarioValidator(BuiltInApplets.catalog))

    @Test
    fun `scenario is switched on and off`() = runBlocking {
        assertEquals(SwitchScenario.Result.Switched, switch("bank", on = true))
        assertEquals(true, repository.get("bank")?.enabled)

        assertEquals(SwitchScenario.Result.Switched, switch("bank", on = false))
        assertEquals(false, repository.get("bank")?.enabled)
    }

    @Test
    fun `scenario with an unavailable applet cannot be switched on, and says why`() = runBlocking {
        assertEquals(
            SwitchScenario.Result.Invalid(listOf(ScenarioProblem.UnknownElements(setOf("module.bridge")))),
            switch("bridge", on = true),
        )
        assertEquals(false, repository.get("bridge")?.enabled)
    }

    @Test
    fun `scenario with problems can always be switched off`() = runBlocking {
        repository.save(unavailable.copy(enabled = true))

        assertEquals(SwitchScenario.Result.Switched, switch("bridge", on = false))
        assertEquals(false, repository.get("bridge")?.enabled)
    }
}
