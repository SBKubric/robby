package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applet.ParamValues
import com.sbkubric.robby.core.engine.Event
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Turns the battery readings the phone reports, in order, into the Battery applet's trigger events.
 *
 * The charger events fire when the plug changes. A threshold trigger fires on crossing its threshold
 * and is then disarmed until the reading is back past it with a margin ([LEVEL_MARGIN] %, [TEMPERATURE_MARGIN] °C):
 * «ниже 20 %» fires going from 20 to 19 and again only after the charge has been 22 % or more.
 * The thresholds are those of the enabled scenarios; one event per crossed threshold carries it in
 * [BatteryApplet.THRESHOLD], and the trigger's filter picks the scenarios with that threshold.
 *
 * What it remembers outlives the process ([BatteryWatchStore]). After a restart the first reading is compared with it
 * like any other: a change of the charger or a crossing of an armed threshold while Robby was not running is reported
 * then, at the time of that reading (it is a change of state seen now, not a missed event), and a threshold that
 * already fired is not re-armed early. With nothing remembered (the first reading ever) and for a threshold it has not
 * seen yet, e.g. of a new scenario, a reading only sets the state.
 */
class BatteryWatch(
    private val scenarios: ScenarioRepository,
    private val store: BatteryWatchStore,
) {
    /** The last plug state and the state of every watched threshold. */
    @Serializable
    data class Memory(val plugged: Boolean, val thresholds: List<Threshold> = emptyList())

    /** A threshold of a trigger, e.g. `battery.below` 20; it fires only while [armed]. */
    @Serializable
    data class Threshold(val trigger: String, val value: Int, val armed: Boolean)

    private val mutex = Mutex()
    private var loaded = false
    private var memory: Memory? = null

    /** The events of this [battery] reading, which happened at [time]. */
    suspend fun onReading(battery: BatteryState, time: Instant): List<Event> = mutex.withLock {
        val before = if (loaded) memory else store.load()
        loaded = true

        val values = BatteryApplet.values(battery)
        val events = mutableListOf<Event>()
        if (before != null && before.plugged != battery.plugged) {
            events += Event(if (battery.plugged) BatteryApplet.PLUGGED else BatteryApplet.UNPLUGGED, values, time)
        }
        val thresholds = thresholdsInUse().map { (trigger, value) ->
            val crossing = crossings.getValue(trigger)
            val known = before?.thresholds?.firstOrNull { it.trigger == trigger && it.value == value }
            when {
                known == null -> Threshold(trigger, value, armed = !crossing.reached(battery, value))
                known.armed && crossing.reached(battery, value) -> {
                    events += Event(trigger, values + (BatteryApplet.THRESHOLD to value.toString()), time)
                    known.copy(armed = false)
                }
                !known.armed && crossing.rearmed(battery, value) -> known.copy(armed = true)
                else -> known
            }
        }

        val after = Memory(battery.plugged, thresholds)
        if (after != before) store.save(after)
        memory = after
        events
    }

    /** Distinct (trigger, threshold) pairs of the enabled scenarios with a threshold trigger. */
    private suspend fun thresholdsInUse(): List<Pair<String, Int>> =
        scenarios.all()
            .filter { it.enabled && it.trigger.id in crossings }
            .mapNotNull { scenario ->
                val crossing = crossings.getValue(scenario.trigger.id)
                ParamValues.number(scenario.trigger.params[crossing.param].orEmpty())?.let { scenario.trigger.id to it }
            }
            .distinct()

    /** When a threshold trigger fires, and when it may fire again. */
    private class Crossing(
        /** The trigger param that holds the threshold. */
        val param: String,
        val reached: (BatteryState, Int) -> Boolean,
        val rearmed: (BatteryState, Int) -> Boolean,
    )

    companion object {
        const val LEVEL_MARGIN = 2
        const val TEMPERATURE_MARGIN = 2

        private val crossings = mapOf(
            BatteryApplet.BELOW to Crossing(BatteryApplet.P_LEVEL, { b, x -> b.level < x }, { b, x -> b.level >= x + LEVEL_MARGIN }),
            BatteryApplet.ABOVE to Crossing(BatteryApplet.P_LEVEL, { b, x -> b.level > x }, { b, x -> b.level <= x - LEVEL_MARGIN }),
            BatteryApplet.HOT to Crossing(
                BatteryApplet.P_TEMPERATURE,
                { b, x -> b.temperature >= x },
                { b, x -> b.temperature <= x - TEMPERATURE_MARGIN },
            ),
        )
    }
}

/** Port: keeps [BatteryWatch.Memory] across process restarts. */
interface BatteryWatchStore {
    /** `null` before the first reading ever. */
    fun load(): BatteryWatch.Memory?
    fun save(memory: BatteryWatch.Memory)
}
