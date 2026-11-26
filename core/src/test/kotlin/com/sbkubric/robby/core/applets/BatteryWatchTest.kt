package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.texts
import com.sbkubric.robby.core.FakeBatteryWatchStore
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryWatchTest {

    private val now = Instant.parse("2026-11-26T11:02:00Z")
    private val store = FakeBatteryWatchStore()

    private fun scenario(trigger: String, vararg params: Pair<String, String>, enabled: Boolean = true, text: String = "{battery.level}") =
        Scenario(
            id = "$trigger ${params.toList()}",
            name = "Батарея",
            enabled = enabled,
            trigger = Trigger(trigger, params.toMap()),
            actions = listOf(Action("telegram.send", mapOf("text" to text))),
        )

    private fun watch(vararg scenarios: Scenario) = BatteryWatch(FakeScenarioRepository(*scenarios), store)

    private fun battery(level: Int, plugged: Boolean = true, temperature: Double = 30.0) = BatteryState(level, temperature, plugged)

    /** Feeds the readings in order and returns what each one reported, as `trigger threshold`. */
    private fun BatteryWatch.feed(vararg readings: BatteryState): List<List<String>> = runBlocking {
        readings.map { reading ->
            onReading(reading, now).map { event ->
                listOfNotNull(event.triggerId, event.values[BatteryApplet.THRESHOLD]).joinToString(" ")
            }
        }
    }

    @Test
    fun `first reading only sets the state, unplugging and plugging are reported once each`() {
        val watch = watch()

        assertEquals(
            listOf(emptyList(), listOf("battery.unplugged"), emptyList(), listOf("battery.plugged")),
            watch.feed(battery(80), battery(80, plugged = false), battery(79, plugged = false), battery(79)),
        )
    }

    @Test
    fun `level below the threshold is reported on crossing and again only after rising 2 % above it`() {
        val watch = watch(scenario("battery.below", "level" to "20"))

        assertEquals(
            listOf(
                emptyList(), emptyList(), emptyList(), listOf("battery.below 20"), emptyList(), // 25 21 20 19 18
                emptyList(), emptyList(), emptyList(), // 21 20 19: back without the margin
                emptyList(), listOf("battery.below 20"), // 22 19
            ),
            watch.feed(*listOf(25, 21, 20, 19, 18, 21, 20, 19, 22, 19).map { battery(it) }.toTypedArray()),
        )
    }

    @Test
    fun `level above the threshold is reported on crossing and again only after falling 2 % below it`() {
        val watch = watch(scenario("battery.above", "level" to "80"))

        assertEquals(
            listOf(
                emptyList(), emptyList(), listOf("battery.above 80"), emptyList(), // 79 80 81 85
                emptyList(), emptyList(), // 79 81: back without the margin
                emptyList(), listOf("battery.above 80"), // 78 81
            ),
            watch.feed(*listOf(79, 80, 81, 85, 79, 81, 78, 81).map { battery(it) }.toTypedArray()),
        )
    }

    @Test
    fun `overheating is reported on reaching the temperature and again only after cooling by 2 degrees`() {
        val watch = watch(scenario("battery.hot", "temperature" to "45"))

        assertEquals(
            listOf(
                emptyList(), listOf("battery.hot 45"), emptyList(), // 44.9 45.0 47.5
                emptyList(), emptyList(), // 43.1 45.0: cooled without the margin
                emptyList(), listOf("battery.hot 45"), // 43.0 46.2
            ),
            watch.feed(*listOf(44.9, 45.0, 47.5, 43.1, 45.0, 43.0, 46.2).map { battery(50, temperature = it) }.toTypedArray()),
        )
    }

    @Test
    fun `each threshold has its own state and scenarios with the same threshold share one event`() {
        val watch = watch(
            scenario("battery.below", "level" to "50"),
            scenario("battery.below", "level" to "20"),
            scenario("battery.below", "level" to " 20 ").copy(id = "second"),
        )

        assertEquals(
            listOf(
                emptyList(), listOf("battery.below 50"), emptyList(), listOf("battery.below 20"), // 60 49 30 19
                emptyList(), listOf("battery.below 50"), // 55 rearms both, 45
            ),
            watch.feed(battery(60), battery(49), battery(30), battery(19), battery(55), battery(45)),
        )
    }

    @Test
    fun `without memory the first reading only sets the state, even below the threshold and unplugged`() {
        val watch = watch(scenario("battery.below", "level" to "20"))

        assertEquals(listOf(emptyList<String>(), emptyList()), watch.feed(battery(15, plugged = false), battery(14, plugged = false)))
    }

    @Test
    fun `disabled scenarios, other triggers and unreadable thresholds are not watched`() {
        val watch = watch(
            scenario("battery.below", "level" to "20", enabled = false),
            scenario("battery.below", "level" to "двадцать"),
            scenario("sms.in", "level" to "20"),
        )

        assertEquals(listOf(emptyList<String>(), emptyList()), watch.feed(battery(30), battery(10)))
    }

    @Test
    fun `threshold of a newly saved scenario starts from the next reading without an event`() = runBlocking {
        val repository = FakeScenarioRepository()
        val watch = BatteryWatch(repository, store)
        watch.onReading(battery(50), now)

        repository.save(scenario("battery.below", "level" to "20"))

        assertEquals(listOf(emptyList(), emptyList(), listOf("battery.below 20")), watch.feed(battery(15), battery(30), battery(19)))
    }

    @Test
    fun `after a restart a threshold already reported still waits for the margin`() {
        val scenarios = arrayOf(scenario("battery.below", "level" to "20"))
        watch(*scenarios).feed(battery(30), battery(19), battery(21))

        // The process died and came back: a new watch over the same store.
        val restarted = watch(*scenarios)

        assertEquals(
            listOf(emptyList(), emptyList(), listOf("battery.below 20")), // 19 (no margin yet), 22, 19
            restarted.feed(battery(19), battery(22), battery(19)),
        )
    }

    @Test
    fun `after a restart unplugging that happened while Robby was not running is reported by the first reading`() {
        watch().feed(battery(50))

        assertEquals(listOf(listOf("battery.unplugged"), emptyList()), watch().feed(battery(50, plugged = false), battery(49, plugged = false)))
    }

    @Test
    fun `after a restart a crossing of an armed threshold that happened while Robby was not running is reported`() {
        val scenarios = arrayOf(scenario("battery.below", "level" to "20"))
        watch(*scenarios).feed(battery(30))

        assertEquals(listOf(listOf("battery.below 20"), emptyList()), watch(*scenarios).feed(battery(15), battery(14)))
    }

    @Test
    fun `events carry the level and the temperature`() = runBlocking {
        val watch = watch(scenario("battery.hot", "temperature" to "40"))
        watch.onReading(battery(50, temperature = 36.6), now)

        assertEquals(
            listOf(
                mapOf("battery.level" to "48", "battery.temperature" to "41,2", "battery.threshold" to "40"),
                mapOf("battery.level" to "48", "battery.temperature" to "41,2"),
            ).toSet(),
            watch.onReading(battery(48, plugged = false, temperature = 41.24), now).map { it.values }.toSet(),
        )
    }

    @Test
    fun `unplugging and discharging below the threshold send exactly one message each`() = runBlocking {
        val sent = mutableListOf<String>()
        val repository = FakeScenarioRepository(
            scenario("battery.unplugged", text = "Зарядка отключена, {battery.level} %"),
            scenario("battery.below", "level" to "20", text = "Заряд {battery.level} %"),
        )
        val watch = BatteryWatch(repository, store)
        val engine = HandleEvent(
            scenarios = repository,
            catalog = BuiltInApplets.catalog,
            log = FakeActivityLog(),
            deviceName = { "Pixel дома" },
            battery = { null },
            call = { null },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        // Reports come often: on every percent, and on voltage and temperature changes with the same level.
        val readings = listOf(battery(23), battery(23, plugged = false), battery(22, plugged = false)) +
            (21 downTo 15).flatMap { listOf(battery(it, plugged = false), battery(it, plugged = false, temperature = 31.0)) } +
            listOf(battery(16, plugged = false), battery(18, plugged = false))
        readings.forEach { reading -> watch.onReading(reading, now).forEach { sent += engine(it).flatMap { run -> run.texts() } } }

        assertEquals(listOf("Зарядка отключена, 23 %", "Заряд 19 %"), sent)
    }
}
