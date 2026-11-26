package com.sbkubric.robby.core.scenario

import com.sbkubric.robby.core.applets.BuiltInApplets
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioValidatorTest {

    private val validator = ScenarioValidator(BuiltInApplets.catalog)

    private fun draft(
        name: String = "SMS от банка",
        trigger: Trigger? = Trigger("sms.in", mapOf("sender" to "900")),
        text: String = "SMS от {sms.sender}: {sms.text}",
    ) = ScenarioDraft(name = name, trigger = trigger, actions = listOf(Action("telegram.send", mapOf("text" to text))))

    @Test
    fun `scenario using its trigger's variables and common ones is valid`() {
        assertEquals(emptyList(), validator.validate(draft(text = "{device}, {now}: {sms.sender} {sms.text}")))
    }

    @Test
    fun `variable the trigger does not provide blocks saving`() {
        val problems = validator.validate(draft(text = "{call.number} {sms.sender} {battery.level}"))
        assertEquals(listOf(ScenarioProblem.UnavailableVariables(setOf("call.number", "battery.level"))), problems)
    }

    @Test
    fun `scenario needs a name, a trigger and at least one action`() {
        val problems = validator.validate(ScenarioDraft(name = " "))
        assertEquals(listOf(ScenarioProblem.NoName, ScenarioProblem.NoTrigger, ScenarioProblem.NoActions), problems)
    }

    @Test
    fun `action with an empty template text blocks saving`() {
        assertEquals(listOf(ScenarioProblem.EmptyText), validator.validate(draft(text = "  ")))
    }

    @Test
    fun `trigger or action missing from the catalog blocks saving`() {
        val problems = validator.validate(
            ScenarioDraft(name = "Старый", trigger = Trigger("call.missed"), actions = listOf(Action("module.bridge"))),
        )
        assertEquals(listOf(ScenarioProblem.UnknownElements(setOf("call.missed", "module.bridge"))), problems)
    }
}
