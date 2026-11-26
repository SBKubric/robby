package com.sbkubric.robby.core.template

/**
 * Message text with `{applet.field}` variables, e.g. `SMS от {sms.sender}`.
 * The result is plain text: connectors send it without markup, so nothing is escaped.
 */
object Template {

    /** What an empty or missing value is rendered as. */
    const val EMPTY_VALUE = "—"

    private val variable = Regex("""\{([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)*)\}""")

    /** Names of the variables the text uses, e.g. `sms.sender`. */
    fun variablesIn(text: String): Set<String> = variable.findAll(text).map { it.groupValues[1] }.toSet()

    /** Replaces every variable with its value; an empty or missing value becomes [EMPTY_VALUE]. */
    fun render(text: String, values: Map<String, String>): String =
        variable.replace(text) { match ->
            values[match.groupValues[1]]?.takeIf { it.isNotBlank() } ?: EMPTY_VALUE
        }
}
