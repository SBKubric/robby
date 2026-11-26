package com.sbkubric.robby.core.template

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateTest {

    @Test
    fun `substitutes variables with event values`() {
        val text = Template.render(
            "SMS от {sms.sender}: {sms.text}",
            mapOf("sms.sender" to "900", "sms.text" to "Код 1234"),
        )
        assertEquals("SMS от 900: Код 1234", text)
    }

    @Test
    fun `empty or missing value becomes a dash`() {
        val text = Template.render("[{sms.sender}] [{sms.text}]", mapOf("sms.sender" to "  "))
        assertEquals("[—] [—]", text)
    }

    @Test
    fun `text that is not a variable stays as typed`() {
        val text = Template.render("{ sms.sender } {\"a\":1} {}", emptyMap())
        assertEquals("{ sms.sender } {\"a\":1} {}", text)
    }
}
