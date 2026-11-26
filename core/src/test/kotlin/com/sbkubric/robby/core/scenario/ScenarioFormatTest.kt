package com.sbkubric.robby.core.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioFormatTest {

    private val scenario = Scenario(
        id = "a1",
        name = "SMS от банка",
        enabled = false,
        trigger = Trigger("sms.in", mapOf("sender" to "900", "text" to "")),
        actions = listOf(Action("telegram.send", mapOf("text" to "SMS от {sms.sender}: {sms.text}"))),
    )

    private val json = """{"schemaVersion":1,"id":"a1","name":"SMS от банка","enabled":false,""" +
        """"trigger":{"id":"sms.in","params":{"sender":"900","text":""}},""" +
        """"actions":[{"id":"telegram.send","params":{"text":"SMS от {sms.sender}: {sms.text}"}}]}"""

    @Test
    fun `scenario is one JSON document with its schema version`() {
        assertEquals(json, ScenarioFormat.encode(scenario))
    }

    @Test
    fun `document decodes back to the same scenario`() {
        assertEquals(scenario, ScenarioFormat.decode(ScenarioFormat.encode(scenario)))
    }

    @Test
    fun `defaults may be left out of a document`() {
        val decoded = ScenarioFormat.decode(
            """{"schemaVersion":1,"id":"b","name":"N","trigger":{"id":"sms.in"},"actions":[{"id":"telegram.send"}]}""",
        )
        assertEquals(Scenario("b", "N", true, Trigger("sms.in"), listOf(Action("telegram.send"))), decoded)
    }

    @Test
    fun `document from a newer schema is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioFormat.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }
}
