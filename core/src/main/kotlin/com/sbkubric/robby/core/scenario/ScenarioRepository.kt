package com.sbkubric.robby.core.scenario

import kotlinx.coroutines.flow.Flow

/** Port: where scenarios are stored. */
interface ScenarioRepository {
    fun observeAll(): Flow<List<Scenario>>
    suspend fun all(): List<Scenario>
    suspend fun get(id: String): Scenario?

    /** Inserts a new scenario or replaces the one with the same id. */
    suspend fun save(scenario: Scenario)
    suspend fun delete(id: String)

    /** Replaces every stored scenario with [scenarios] at once: no one sees a mix of the old and the new. */
    suspend fun replaceAll(scenarios: List<Scenario>)
}
