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
    /**
     * What happened, e.g. where the message was delivered or why the action failed (text from the applet).
     * For an [error] of the engine itself it is the detail, e.g. the action id.
     */
    val message: String?,
    /** A failure found by the engine itself; the UI turns it into text. `null` for applet results. */
    val error: RunError? = null,
)

enum class RunStatus { DONE, FAILED }

enum class RunError {
    /** No performer is registered for the action ([Run.message] holds its id). */
    NO_PERFORMER,

    /** The actions did not finish within the time the caller allowed. */
    TIMED_OUT,
}

/** Port: the history of runs, newest first. */
interface ActivityLog {
    suspend fun add(run: Run)
    fun observeRecent(): Flow<List<Run>>

    /** The newest run of each scenario, by scenario id. */
    fun observeLastRuns(): Flow<Map<String, Run>>

    /** Deletes runs older than [olderThan] and all but the [keepNewest] newest ones. */
    suspend fun prune(olderThan: Instant, keepNewest: Int)
}
