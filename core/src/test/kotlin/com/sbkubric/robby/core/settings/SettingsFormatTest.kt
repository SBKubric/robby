package com.sbkubric.robby.core.settings

import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioFormat
import com.sbkubric.robby.core.scenario.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFormatTest {

    private val scenario = Scenario(
        id = "a1",
        name = "SMS от банка",
        trigger = Trigger("sms.in", mapOf("sender" to "900")),
        actions = listOf(Action("telegram.send", mapOf("text" to "{sms.text}"))),
    )

    private val settings = SettingsExport(scenarios = listOf(scenario), serverPhoneName = "Pixel дома")

    @Test
    fun `export file holds the server phone's name and each scenario as its own scenario document`() {
        assertEquals(
            """{"format":"robby-settings","schemaVersion":1,"serverPhoneName":"Pixel дома",""" +
                """"scenarios":[${ScenarioFormat.encode(scenario)}]}""",
            SettingsFormat.encode(settings),
        )
    }

    @Test
    fun `export file reads back as the same settings`() {
        assertEquals(SettingsFormat.Decoded.Read(settings), SettingsFormat.decode(SettingsFormat.encode(settings)))
        val noName = settings.copy(serverPhoneName = null)
        assertEquals(SettingsFormat.Decoded.Read(noName), SettingsFormat.decode(SettingsFormat.encode(noName)))
    }

    @Test
    fun `file from a newer Robby is refused as newer`() {
        val newer = SettingsFormat.encode(settings).replace("\"schemaVersion\":1,\"server", "\"schemaVersion\":2,\"server")

        assertEquals(SettingsFormat.Decoded.NewerVersion, SettingsFormat.decode(newer))
    }

    @Test
    fun `file whose scenario comes from a newer scenario format is refused as newer too`() {
        val newer = SettingsFormat.encode(settings)
            .replace("\"schemaVersion\":${ScenarioFormat.SCHEMA_VERSION},\"id\"", "\"schemaVersion\":99,\"id\"")

        assertEquals(SettingsFormat.Decoded.NewerVersion, SettingsFormat.decode(newer))
    }

    @Test
    fun `anything else is not a settings file`() {
        listOf(
            "",
            "не JSON",
            "[]",
            """{"schemaVersion":1,"scenarios":[]}""",
            """{"format":"robby-settings","schemaVersion":1,"scenarios":[{"name":"без id"}]}""",
            """{"format":"robby-settings","schemaVersion":1,"scenarios":[$scenarioJson,$scenarioJson]}""",
            ScenarioFormat.encode(scenario),
        ).forEach { text ->
            assertEquals(SettingsFormat.Decoded.NotSettings, SettingsFormat.decode(text), text)
        }
    }

    private val scenarioJson get() = ScenarioFormat.encode(scenario)

    @Test
    fun `fields a later version adds are ignored`() {
        val text = """{"format":"robby-settings","schemaVersion":1,"theme":"dark","scenarios":[]}"""

        assertEquals(SettingsFormat.Decoded.Read(SettingsExport(emptyList(), null)), SettingsFormat.decode(text))
    }
}
