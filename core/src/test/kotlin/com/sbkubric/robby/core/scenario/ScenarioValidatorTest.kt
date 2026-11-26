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

    @Test
    fun `condition missing from the catalog blocks saving`() {
        val problems = validator.validate(draft().copy(conditions = listOf(Condition("system.days", mapOf("days" to "1")), Condition("battery.level"))))
        assertEquals(listOf(ScenarioProblem.UnknownElements(setOf("battery.level"))), problems)
    }

    @Test
    fun `unreadable time or no weekdays in the schedule or a condition blocks saving`() {
        val problems = validator.validate(
            ScenarioDraft(
                name = "Пульс",
                trigger = Trigger("system.schedule", mapOf("days" to "", "time" to "9:00")),
                conditions = listOf(Condition("system.window", mapOf("from" to "24:00", "to" to "08:00"))),
                actions = listOf(Action("system.notify", mapOf("title" to "{device}", "text" to "на связи {now}"))),
            ),
        )
        assertEquals(
            listOf(
                ScenarioProblem.InvalidParams(
                    setOf(ScenarioProblem.ParamRef("system.schedule", "days"), ScenarioProblem.ParamRef("system.window", "from")),
                ),
            ),
            problems,
        )
    }

    @Test
    fun `notification needs both a title and a text`() {
        val problems = validator.validate(
            ScenarioDraft(
                name = "Включился",
                trigger = Trigger("system.boot"),
                actions = listOf(Action("system.notify", mapOf("title" to "{device}"))),
            ),
        )
        assertEquals(listOf(ScenarioProblem.EmptyText), problems)
    }
}
