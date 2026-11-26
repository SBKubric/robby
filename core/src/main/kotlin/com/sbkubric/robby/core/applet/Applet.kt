package com.sbkubric.robby.core.applet

import java.time.ZonedDateTime

/**
 * A built-in module that offers triggers, conditions and actions to scenarios (ADR 0001).
 * Ids are part of the stored scenario format and never change.
 * Labels are the Russian UI text: the catalog is domain data, and `:core` cannot see Android resources.
 */
data class Applet(
    val id: String,
    val name: String,
    val triggers: List<TriggerSpec> = emptyList(),
    val conditions: List<ConditionSpec> = emptyList(),
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

/**
 * A condition an applet offers, e.g. `system.window`: a check of the server phone's state at the time of the event,
 * not of the event itself.
 */
data class ConditionSpec(
    val id: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
    private val check: ConditionCheck,
) {
    /** True if the phone's [state] passes the condition with the scenario's [params]. */
    fun isMet(params: Map<String, String>, state: ServerPhoneState): Boolean = check.isMet(params, state)
}

/** How a condition judges the phone's state; unreadable params mean the condition is not met. */
fun interface ConditionCheck {
    fun isMet(params: Map<String, String>, state: ServerPhoneState): Boolean
}

/**
 * What conditions can see of the server phone, taken once per event.
 * Later slices add fields read through their own ports.
 */
data class ServerPhoneState(
    /** The time of the event in the phone's time zone. */
    val time: ZonedDateTime,
    /** The battery at the time of the event; `null` if the phone did not report it. */
    val battery: BatteryState? = null,
)

/** One reading of the server phone's battery. */
data class BatteryState(
    /** Charge, 0–100 %. */
    val level: Int,
    /** In °C. */
    val temperature: Double,
    /** On a charger, also when full and no longer charging. */
    val plugged: Boolean,
)

/** An action an applet offers, e.g. `telegram.send`. */
data class ActionSpec(
    val id: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
)

/** A parameter of a trigger, condition or action; its value is stored as text. */
data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind = ParamKind.TEXT,
    /** For a trigger param: how it filters the event. An empty param lets any event through. */
    val filter: Filter? = null,
    /** The value a newly added element starts with. */
    val default: String = "",
    /** For a [ParamKind.NUMBER]: the numbers it accepts. */
    val range: IntRange? = null,
    /** For a [ParamKind.NUMBER]: what it counts, e.g. `%`, shown after the value. */
    val unit: String? = null,
) {
    /** A template text: may contain variables, which are rendered before the action runs. */
    val isTemplate: Boolean get() = kind == ParamKind.TEMPLATE

    /** False if the text cannot be read as the param's kind or is out of its [range]. */
    fun isValid(value: String): Boolean =
        kind.isValid(value) && (range == null || ParamValues.number(value)?.let { it in range } == true)
}

/** What a param's text holds, which decides how it is edited and checked. */
enum class ParamKind {
    /** Free text. */
    TEXT,

    /** Text with variables, e.g. `SMS от {sms.sender}`. */
    TEMPLATE,

    /** A time of day `HH:MM` ([ParamValues.time]). */
    TIME,

    /** A non-empty set of weekdays, ISO numbers `1,2,3` for Mon, Tue, Wed ([ParamValues.weekdays]). */
    WEEKDAYS,

    /** A whole number, e.g. a threshold of `20` % ([ParamValues.number]). */
    NUMBER,
    ;

    /** False if the text cannot be read as this kind; text kinds accept anything. */
    fun isValid(value: String): Boolean = when (this) {
        TEXT, TEMPLATE -> true
        TIME -> ParamValues.time(value) != null
        WEEKDAYS -> ParamValues.weekdays(value) != null
        NUMBER -> ParamValues.number(value) != null
    }
}

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

    /** The event's time of day (`HH:MM`) is the param's time. */
    data class AtTime(override val variable: String) : Filter {
        override fun passes(wanted: String, actual: String) =
            ParamValues.time(wanted)?.let { it == ParamValues.time(actual) } ?: false
    }

    /** The event crossed the param's threshold: both are the same whole number. */
    data class AtThreshold(override val variable: String) : Filter {
        override fun passes(wanted: String, actual: String) =
            ParamValues.number(wanted)?.let { it == ParamValues.number(actual) } ?: false
    }

    /** The event's weekday (an ISO number) is one of the param's weekdays. */
    data class OnDays(override val variable: String) : Filter {
        override fun passes(wanted: String, actual: String): Boolean {
            val day = ParamValues.weekdays(actual)?.singleOrNull() ?: return false
            return ParamValues.weekdays(wanted)?.contains(day) ?: false
        }
    }
}
