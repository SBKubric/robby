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
        conditions = listOf(Condition("system.window", mapOf("from" to "22:00", "to" to "08:00"))),
        actions = listOf(Action("telegram.send", mapOf("text" to "SMS от {sms.sender}: {sms.text}"))),
        cooldownMinutes = 15,
    )

    private val json = """{"schemaVersion":2,"id":"a1","name":"SMS от банка","enabled":false,""" +
        """"trigger":{"id":"sms.in","params":{"sender":"900","text":""}},""" +
        """"conditions":[{"id":"system.window","params":{"from":"22:00","to":"08:00"}}],""" +
        """"actions":[{"id":"telegram.send","params":{"text":"SMS от {sms.sender}: {sms.text}"}}],""" +
        """"cooldownMinutes":15}"""

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
            """{"schemaVersion":2,"id":"b","name":"N","trigger":{"id":"sms.in"},"actions":[{"id":"telegram.send"}]}""",
        )
        assertEquals(Scenario("b", "N", true, Trigger("sms.in"), actions = listOf(Action("telegram.send"))), decoded)
    }

    @Test
    fun `version 1 document reads as a scenario without conditions and without a pause`() {
        val decoded = ScenarioFormat.decode(
            """{"schemaVersion":1,"id":"a1","name":"SMS от банка","enabled":true,""" +
                """"trigger":{"id":"sms.in","params":{"sender":"900"}},""" +
                """"actions":[{"id":"telegram.send","params":{"text":"{sms.text}"}}]}""",
        )
        assertEquals(
            Scenario(
                id = "a1",
                name = "SMS от банка",
                trigger = Trigger("sms.in", mapOf("sender" to "900")),
                conditions = emptyList(),
                actions = listOf(Action("telegram.send", mapOf("text" to "{sms.text}"))),
                cooldownMinutes = 0,
            ),
            decoded,
        )
    }

    @Test
    fun `document from a newer schema is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioFormat.decode(json.replace("\"schemaVersion\":2", "\"schemaVersion\":3"))
        }
    }
}
