package com.sbkubric.robby.core.applet

import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

class AppletCatalogTest {

    private val catalog = BuiltInApplets.catalog

    private fun scenario(trigger: Trigger, vararg actions: Action) = Scenario("id", "Имя", true, trigger, actions = actions.toList())

    @Test
    fun `description lists trigger with its filled filters and actions with their applets`() {
        val description = catalog.describe(
            scenario(
                Trigger("sms.in", mapOf("sender" to "900", "text" to "код")),
                Action("telegram.send", mapOf("text" to "{sms.text}")),
            ),
        )
        assertEquals(
            ScenarioDescription(
                trigger = "Входящее SMS",
                filters = listOf("900", "код"),
                actions = listOf(ScenarioDescription.Step("Telegram", "Отправить сообщение в группу")),
            ),
            description,
        )
    }

    @Test
    fun `description leaves out empty filters and shows unknown elements by id`() {
        val description = catalog.describe(scenario(Trigger("sms.in", mapOf("sender" to " ")), Action("module.bridge")))
        assertEquals(
            ScenarioDescription("Входящее SMS", emptyList(), listOf(ScenarioDescription.Step(null, "module.bridge"))),
            description,
        )
    }

    @Test
    fun `applets are offered by the kind of element they have`() {
        assertEquals(listOf("system", "battery", "sms", "call"), catalog.appletsWithTriggers().map { it.id })
        assertEquals(listOf("system", "battery", "call"), catalog.appletsWithConditions().map { it.id })
        assertEquals(listOf("system", "telegram"), catalog.appletsWithActions().map { it.id })
    }

    @Test
    fun `description shows schedule, conditions with readable values and the pause`() {
        val description = catalog.describe(
            Scenario(
                id = "pulse",
                name = "Пульс",
                trigger = Trigger("system.schedule", mapOf("days" to "1,2,3,4,5,6,7", "time" to "9:00")),
                conditions = listOf(
                    Condition("system.days", mapOf("days" to "1,3,5")),
                    Condition("system.window", mapOf("from" to "22:00", "to" to "08:00")),
                    Condition("battery.level"),
                ),
                actions = listOf(Action("telegram.send", mapOf("text" to "{device} на связи"))),
                cooldownMinutes = 60,
            ),
        )
        assertEquals(
            ScenarioDescription(
                trigger = "По расписанию",
                filters = listOf("каждый день", "09:00"),
                actions = listOf(ScenarioDescription.Step("Telegram", "Отправить сообщение в группу")),
                conditions = listOf(
                    ScenarioDescription.Check("Дни недели", listOf("пн, ср, пт")),
                    ScenarioDescription.Check("Окно времени", listOf("22:00", "08:00")),
                    ScenarioDescription.Check("battery.level", emptyList()),
                ),
                cooldownMinutes = 60,
            ),
            description,
        )
    }

    @Test
    fun `description shows thresholds with their units`() {
        val description = catalog.describe(
            Scenario(
                id = "low",
                name = "Разряжается",
                trigger = Trigger("battery.below", mapOf("level" to "20")),
                conditions = listOf(Condition("battery.level_above", mapOf("level" to "5")), Condition("battery.not_charging")),
                actions = listOf(Action("telegram.send", mapOf("text" to "Заряд {battery.level} %"))),
            ),
        )
        assertEquals(
            ScenarioDescription(
                trigger = "Заряд ниже порога",
                filters = listOf("20 %"),
                actions = listOf(ScenarioDescription.Step("Telegram", "Отправить сообщение в группу")),
                conditions = listOf(
                    ScenarioDescription.Check("Заряд выше", listOf("5 %")),
                    ScenarioDescription.Check("Не на зарядке", emptyList()),
                ),
            ),
            description,
        )
    }
}
