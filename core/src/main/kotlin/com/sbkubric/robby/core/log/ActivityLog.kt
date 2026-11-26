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
     * For a [reason] of the engine itself it is the detail, e.g. the action or condition id.
     */
    val message: String?,
    /** Why the engine itself failed or skipped the run; the UI turns it into text. `null` for applet results. */
    val reason: RunReason? = null,
)

enum class RunStatus {
    /** Every action was done. */
    DONE,

    /** An action failed, or the engine could not finish the run. */
    FAILED,

    /** The actions did not run: a condition was not met or the scenario was within its pause. */
    SKIPPED,
}

enum class RunReason {
    /** [RunStatus.FAILED]: no performer is registered for the action ([Run.message] holds its id). */
    NO_PERFORMER,

    /** [RunStatus.FAILED]: the actions did not finish within the time the caller allowed. */
    TIMED_OUT,

    /** [RunStatus.SKIPPED]: a condition was not met ([Run.message] holds its id). */
    CONDITION_NOT_MET,

    /** [RunStatus.SKIPPED]: «пауза» — the scenario's last executed run was less than its cooldown ago. */
    COOLDOWN,
}

/** Port: the history of runs, newest first. */
interface ActivityLog {
    suspend fun add(run: Run)
    fun observeRecent(): Flow<List<Run>>

    /** The newest run of each scenario, by scenario id. */
    fun observeLastRuns(): Flow<Map<String, Run>>

    /** The newest run of the scenario whose actions ran, i.e. not [RunStatus.SKIPPED]: the pause counts from it. */
    suspend fun lastExecuted(scenarioId: String): Run?

    /** Deletes runs older than [olderThan] and all but the [keepNewest] newest ones. */
    suspend fun prune(olderThan: Instant, keepNewest: Int)
}
