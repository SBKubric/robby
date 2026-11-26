package com.sbkubric.robby.core.applet

/**
 * A built-in module that offers triggers and actions to scenarios (ADR 0001).
 * Ids are part of the stored scenario format and never change.
 * Labels are the Russian UI text: the catalog is domain data, and `:core` cannot see Android resources.
 */
data class Applet(
    val id: String,
    val name: String,
    val triggers: List<TriggerSpec> = emptyList(),
    val actions: List<ActionSpec> = emptyList(),
    /** A connector needs an external account and its own setup («Активные апплеты»). */
    val isConnector: Boolean = false,
)

/** A trigger an applet offers, e.g. `sms.in`. */
data class TriggerSpec(
    val id: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
    /** Variables its events carry, e.g. `sms.sender`. */
    val variables: List<String> = emptyList(),
) {
    /** True if the event's [values] pass every filter param of the scenario's trigger [params]. */
    fun matches(params: Map<String, String>, values: Map<String, String>): Boolean =
        this.params.all { spec ->
            val filter = spec.filter ?: return@all true
            val wanted = params[spec.key].orEmpty().trim()
            wanted.isEmpty() || filter.passes(wanted, values[filter.variable].orEmpty())
        }
}

/** An action an applet offers, e.g. `telegram.send`. */
data class ActionSpec(
    val id: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
)

/** A parameter of a trigger or an action, edited as text. */
data class ParamSpec(
    val key: String,
    val label: String,
    /** A template text: may contain variables, which are rendered before the action runs. */
    val isTemplate: Boolean = false,
    /** For a trigger param: how it filters the event. An empty param lets any event through. */
    val filter: Filter? = null,
)

/** How a trigger param checks one of the event's variables. */
sealed interface Filter {
    val variable: String

    fun passes(wanted: String, actual: String): Boolean

    /** Same value, ignoring case and the spaces, dashes and brackets of phone number formatting. */
    data class Equals(override val variable: String) : Filter {
        override fun passes(wanted: String, actual: String) = normalize(wanted).equals(normalize(actual), ignoreCase = true)

        private fun normalize(value: String) = value.filterNot { it.isWhitespace() || it in "-()" }
    }

    /** The value contains the param's text, ignoring case. */
    data class Contains(override val variable: String) : Filter {
        override fun passes(wanted: String, actual: String) = actual.contains(wanted, ignoreCase = true)
    }
}
