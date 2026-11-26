package com.sbkubric.robby.core

import com.sbkubric.robby.core.applets.BatteryWatch
import com.sbkubric.robby.core.applets.BatteryWatchStore
import com.sbkubric.robby.core.applets.CallWatch
import com.sbkubric.robby.core.applets.CallWatchStore
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

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

    override suspend fun add(run: Run) { runs.value = listOf(run) + runs.value }
    override fun observeRecent(): Flow<List<Run>> = runs
    override fun observeLastRuns(): Flow<Map<String, Run>> =
        runs.map { list -> list.groupBy { it.scenarioId }.mapValues { (_, r) -> r.first() } }
    override suspend fun lastExecuted(scenarioId: String): Run? =
        runs.value.filter { it.scenarioId == scenarioId && it.status != RunStatus.SKIPPED }.maxByOrNull { it.time }
    override suspend fun prune(olderThan: Instant, keepNewest: Int) {
        runs.value = runs.value.filter { !it.time.isBefore(olderThan) }.take(keepNewest)
    }
}

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
