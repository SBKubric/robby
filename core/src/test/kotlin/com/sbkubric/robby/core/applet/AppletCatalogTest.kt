package com.sbkubric.robby.core.applet

import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

class AppletCatalogTest {

    private val catalog = BuiltInApplets.catalog

    private fun scenario(trigger: Trigger, vararg actions: Action) = Scenario("id", "Имя", true, trigger, actions.toList())

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
        assertEquals(listOf("sms"), catalog.appletsWithTriggers().map { it.id })
        assertEquals(listOf("telegram"), catalog.appletsWithActions().map { it.id })
    }
}
