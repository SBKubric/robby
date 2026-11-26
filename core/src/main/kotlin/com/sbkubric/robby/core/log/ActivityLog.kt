package com.sbkubric.robby.core.log

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** One execution of a scenario and its result, as the activity log keeps it. */
data class Run(
    val scenarioId: String,
    /** The name at the time of the run: the log outlives renames and deletions. */
    val scenarioName: String,
    /** Time of the event that started the run. */
    val time: Instant,
    val status: RunStatus,
    /** What happened, e.g. where the message was delivered or why the action failed. */
    val message: String?,
)

enum class RunStatus { DONE, FAILED }

/** Port: the history of runs, newest first. */
interface ActivityLog {
    suspend fun add(run: Run)
    fun observeRecent(): Flow<List<Run>>

    /** The newest run of each scenario, by scenario id. */
    fun observeLastRuns(): Flow<Map<String, Run>>

    /** Deletes runs older than [olderThan] and all but the [keepNewest] newest ones. */
    suspend fun prune(olderThan: Instant, keepNewest: Int)
}
