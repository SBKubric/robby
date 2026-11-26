package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.ConditionSpec
import com.sbkubric.robby.core.applet.Filter
import com.sbkubric.robby.core.applet.ParamKind
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.applet.ParamValues
import com.sbkubric.robby.core.applet.TriggerSpec
import java.util.Locale

/**
 * «Батарея»: charge, charger and temperature of the server phone.
 * Threshold triggers fire on crossing, with a margin before they fire again ([BatteryWatch]);
 * the receiver lives in `:app`'s service, the state for conditions comes through `BatteryStateProvider`.
 */
object BatteryApplet {
    const val ID = "battery"

    const val BELOW = "battery.below"
    const val ABOVE = "battery.above"
    const val PLUGGED = "battery.plugged"
    const val UNPLUGGED = "battery.unplugged"
    const val HOT = "battery.hot"

    const val LEVEL_ABOVE = "battery.level_above"
    const val CHARGING = "battery.charging"
    const val NOT_CHARGING = "battery.not_charging"

    /** Variables: `48` (%) and `41,2` (°C). */
    const val LEVEL = "battery.level"
    const val TEMPERATURE = "battery.temperature"

    /** Param keys. */
    const val P_LEVEL = "level"
    const val P_TEMPERATURE = "temperature"

    /** The threshold an event crossed; it selects the scenarios with that threshold, templates do not offer it. */
    const val THRESHOLD = "battery.threshold"

    private val variables = listOf(LEVEL, TEMPERATURE)
    private val percent = 1..99

    private fun levelParam(default: Int, filter: Filter? = null) =
        ParamSpec(P_LEVEL, "Порог, %", ParamKind.NUMBER, filter, default = default.toString(), range = percent, unit = "%")

    val applet = Applet(
        id = ID,
        name = "Батарея",
        triggers = listOf(
            TriggerSpec(BELOW, "Заряд ниже порога", listOf(levelParam(20, Filter.AtThreshold(THRESHOLD))), variables),
            TriggerSpec(ABOVE, "Заряд выше порога", listOf(levelParam(80, Filter.AtThreshold(THRESHOLD))), variables),
            TriggerSpec(PLUGGED, "Зарядка подключена", variables = variables),
            TriggerSpec(UNPLUGGED, "Зарядка отключена", variables = variables),
            TriggerSpec(
                HOT,
                "Перегрев",
                listOf(
                    ParamSpec(
                        P_TEMPERATURE, "Температура, °C", ParamKind.NUMBER, Filter.AtThreshold(THRESHOLD),
                        default = "45", range = 30..80, unit = "°C",
                    ),
                ),
                variables,
            ),
        ),
        conditions = listOf(
            ConditionSpec(LEVEL_ABOVE, "Заряд выше", listOf(levelParam(50))) { params, state ->
                val threshold = ParamValues.number(params[P_LEVEL].orEmpty())
                val battery = state.battery
                threshold != null && battery != null && battery.level > threshold
            },
            ConditionSpec(CHARGING, "На зарядке") { _, state -> state.battery?.plugged == true },
            ConditionSpec(NOT_CHARGING, "Не на зарядке") { _, state -> state.battery?.plugged == false },
        ),
    )

    private val russian = Locale.forLanguageTag("ru")

    /** The variables of an event from this [battery] reading. */
    fun values(battery: BatteryState): Map<String, String> = mapOf(
        LEVEL to battery.level.toString(),
        TEMPERATURE to "%.1f".format(russian, battery.temperature),
    )
}
