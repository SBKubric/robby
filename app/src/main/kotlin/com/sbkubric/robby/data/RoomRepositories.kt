package com.sbkubric.robby.data

import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunAction
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioFormat
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

class RoomScenarioRepository(private val dao: ScenarioDao) : ScenarioRepository {
    override fun observeAll(): Flow<List<Scenario>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toScenario() } }
    override suspend fun all(): List<Scenario> = dao.all().mapNotNull { it.toScenario() }
    override suspend fun get(id: String): Scenario? = dao.get(id)?.toScenario()
    override suspend fun save(scenario: Scenario) =
        dao.upsert(ScenarioEntity(scenario.id, scenario.name, ScenarioFormat.encode(scenario)))
    override suspend fun delete(id: String) = dao.delete(id)

    // A document this build cannot read is skipped rather than crashing the list or the engine.
    private fun ScenarioEntity.toScenario(): Scenario? = runCatching { ScenarioFormat.decode(json) }.getOrNull()
}

class RoomActivityLog(private val dao: RunDao) : ActivityLog {
    override suspend fun add(run: Run): Run = run.copy(id = dao.insert(run.toEntity().copy(id = 0)))

    override suspend fun update(run: Run) = dao.update(run.toEntity())

    override suspend fun pending(): List<Run> = dao.pending(RunStatus.PENDING.name).map { it.toRun() }

    override fun observeRecent(): Flow<List<Run>> = dao.observeRecent(SHOWN_RUNS).map { rows -> rows.map { it.toRun() } }

    override fun observeLastRuns(): Flow<Map<String, Run>> =
        dao.observeLastRuns().map { rows -> rows.associate { it.scenarioId to it.toRun() } }

    override suspend fun lastExecuted(scenarioId: String): Run? =
        dao.lastExecuted(scenarioId, excluded = listOf(RunStatus.SKIPPED.name, RunStatus.CANCELLED.name))?.toRun()

    override suspend fun prune(olderThan: Instant, keepNewest: Int) =
        dao.prune(olderThan.toEpochMilli(), keepNewest, pending = RunStatus.PENDING.name)

    private fun Run.toEntity() = RunEntity(
        id = id,
        scenarioId = scenarioId,
        scenarioName = scenarioName,
        timeMillis = time.toEpochMilli(),
        status = status.name,
        message = message,
        error = reason?.name,
        actions = actions.takeIf { it.isNotEmpty() }?.let { json.encodeToString(snapshot, it) },
        attempts = attempts,
        nextAttemptMillis = nextAttemptAt?.toEpochMilli(),
        late = late,
    )

    private fun RunEntity.toRun() = Run(
        scenarioId = scenarioId,
        scenarioName = scenarioName,
        time = Instant.ofEpochMilli(timeMillis),
        status = runCatching { RunStatus.valueOf(status) }.getOrDefault(RunStatus.FAILED),
        message = message,
        reason = error?.let { name -> RunReason.entries.firstOrNull { it.name == name } },
        id = id,
        actions = actions?.let { runCatching { json.decodeFromString(snapshot, it) }.getOrNull() }.orEmpty(),
        attempts = attempts,
        nextAttemptAt = nextAttemptMillis?.let(Instant::ofEpochMilli),
        late = late,
    )

    private companion object {
        // The log never holds more than 2000 runs (HandleEvent prunes it).
        const val SHOWN_RUNS = 2000

        val json = Json { ignoreUnknownKeys = true }
        val snapshot = ListSerializer(RunAction.serializer())
    }
}
