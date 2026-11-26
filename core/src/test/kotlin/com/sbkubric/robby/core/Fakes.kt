package com.sbkubric.robby.core

import com.sbkubric.robby.core.applets.BatteryWatch
import com.sbkubric.robby.core.applets.BatteryWatchStore
import com.sbkubric.robby.core.applets.CallWatch
import com.sbkubric.robby.core.applets.CallWatchStore
import com.sbkubric.robby.core.engine.ConnectorHealth
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class FakeScenarioRepository(vararg initial: Scenario) : ScenarioRepository {
    private val state = MutableStateFlow(initial.associateBy { it.id })

    override fun observeAll(): Flow<List<Scenario>> = state.map { it.values.toList() }
    override suspend fun all() = state.value.values.toList()
    override suspend fun get(id: String) = state.value[id]
    override suspend fun save(scenario: Scenario) { state.value += scenario.id to scenario }
    override suspend fun delete(id: String) { state.value -= id }
}

class FakeActivityLog : ActivityLog {
    val runs = MutableStateFlow(emptyList<Run>())
    private var lastId = 0L

    // yield() stands for the database's suspension, so that concurrent callers interleave as they would on a phone.
    override suspend fun add(run: Run): Run {
        yield()
        val stored = run.copy(id = ++lastId)
        runs.value = listOf(stored) + runs.value
        return stored
    }

    override suspend fun update(run: Run) {
        runs.value = runs.value.map { if (it.id == run.id) run else it }
    }

    override suspend fun pending(): List<Run> =
        runs.value.filter { it.status == RunStatus.PENDING }.sortedWith(compareBy({ it.time }, { it.id }))

    override fun observeRecent(): Flow<List<Run>> = runs
    override fun observeLastRuns(): Flow<Map<String, Run>> =
        runs.map { list -> list.groupBy { it.scenarioId }.mapValues { (_, r) -> r.first() } }

    override suspend fun lastExecuted(scenarioId: String): Run? {
        yield()
        return runs.value
            .filter { it.scenarioId == scenarioId && it.status != RunStatus.SKIPPED && it.status != RunStatus.CANCELLED }
            .maxByOrNull { it.time }
    }

    override suspend fun prune(olderThan: Instant, keepNewest: Int) {
        val kept = runs.value.filter { !it.time.isBefore(olderThan) }.take(keepNewest).toSet()
        runs.value = runs.value.filter { it in kept || it.status == RunStatus.PENDING }
    }

    /** The run with this id as it is now. */
    fun get(id: Long): Run = runs.value.single { it.id == id }
}

/** Keeps connector health as the app's preferences would. */
class FakeConnectorHealth : ConnectorHealth {
    val broken = mutableMapOf<String, String>()

    override fun brokenReason(connectorId: String) = broken[connectorId]
    override suspend fun markBroken(connectorId: String, reason: String) { broken[connectorId] = reason }
}

/** A clock the test moves by hand. */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneId.of("Europe/Moscow")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

/** The rendered texts of a run's snapshot, in order: what its actions will send. */
fun Run.texts(): List<String> = actions.mapNotNull { it.params["text"] }

/** Keeps the battery watch's memory as the app's preferences would: it outlives a [BatteryWatch] instance. */
class FakeBatteryWatchStore : BatteryWatchStore {
    var memory: BatteryWatch.Memory? = null

    override fun load() = memory
    override fun save(memory: BatteryWatch.Memory) { this.memory = memory }
}

/** Keeps the call watch's memory as the app's preferences would: it outlives a [CallWatch] instance. */
class FakeCallWatchStore : CallWatchStore {
    var ringing: CallWatch.Ringing? = null

    override fun load() = ringing
    override fun save(ringing: CallWatch.Ringing?) { this.ringing = ringing }
}
