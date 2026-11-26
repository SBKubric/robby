package com.sbkubric.robby.core.log

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One execution of a scenario and its result, as the activity log keeps it.
 *
 * A run the engine decided to execute is recorded at once as [RunStatus.PENDING] with a snapshot of its actions
 * ([actions]); delivery then performs them and moves the run on (see `DeliverRuns`).
 */
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
     * While [RunStatus.PENDING], the last temporary failure, if any.
     */
    val message: String?,
    /** Why the engine itself failed, skipped or cancelled the run; the UI turns it into text. `null` for applet results. */
    val reason: RunReason? = null,
    /** Given by the log when the run is added; 0 before. */
    val id: Long = 0,
    /** The scenario's actions at the time of the event, their templates already rendered: later edits do not change them. */
    val actions: List<RunAction> = emptyList(),
    /** Failed attempts so far: the delay before the next one grows with them. */
    val attempts: Int = 0,
    /** While [RunStatus.PENDING] after a temporary failure: not tried again before this time. */
    val nextAttemptAt: Instant? = null,
    /**
     * While [RunStatus.PENDING] after the service itself asked to wait (`retry_after`): the network coming back
     * does not move the next attempt before this time.
     */
    val notBefore: Instant? = null,
    /** An action was performed late and its text got «⏱ HH:MM» of the event («с опозданием»). */
    val late: Boolean = false,
)

/** One action of a run's snapshot: what to perform, and whether it is already done. */
@Serializable
data class RunAction(
    /** The applet action id, e.g. `telegram.send`. */
    val id: String,
    /** Params with templates rendered with the event's variables. */
    val params: Map<String, String> = emptyMap(),
    /** Done: a retry of the run does not perform it again. */
    val done: Boolean = false,
    /** What the applet said when it was done. */
    val note: String? = null,
)

enum class RunStatus {
    /** «в очереди»: recorded, its actions not (all) performed yet; delivery may be waiting to retry. */
    PENDING,

    /** Every action was done. */
    DONE,

    /** «не выполнено»: an action failed for good, the run gave up after 24 h, or the engine could not run it. */
    FAILED,

    /** The actions did not run: a condition was not met or the scenario was within its pause. */
    SKIPPED,

    /** «отменено»: the scenario was switched off or deleted while the run waited. */
    CANCELLED,
}

enum class RunReason {
    /** [RunStatus.FAILED]: no performer is registered for the action ([Run.message] holds its id). */
    NO_PERFORMER,

    /** [RunStatus.FAILED]: before slice 6, a run that did not finish within the broadcast's time; only old log rows. */
    TIMED_OUT,

    /** [RunStatus.SKIPPED]: a condition was not met ([Run.message] holds its id). */
    CONDITION_NOT_MET,

    /** [RunStatus.SKIPPED]: «пауза» — the scenario's last executed run was less than its cooldown ago. */
    COOLDOWN,

    /** [RunStatus.FAILED]: temporary failures for 24 h since the event ([Run.message] holds the last one). */
    GAVE_UP,

    /** [RunStatus.FAILED]: the connector is «Сломан» ([Run.message] holds why). */
    CONNECTOR_BROKEN,

    /** [RunStatus.CANCELLED]: the scenario was switched off. */
    SCENARIO_DISABLED,

    /** [RunStatus.CANCELLED]: the scenario was deleted. */
    SCENARIO_DELETED,
}

/** Port: the history of runs, newest first; it also holds the pending runs that delivery works through. */
interface ActivityLog {
    /** Stores a new run; returns it with its [Run.id]. */
    suspend fun add(run: Run): Run

    /** Replaces the stored run with the same [Run.id]. */
    suspend fun update(run: Run)

    /** The [RunStatus.PENDING] runs, oldest event first (then in the order they were added). */
    suspend fun pending(): List<Run>

    fun observeRecent(): Flow<List<Run>>

    /** The newest run of each scenario, by scenario id. */
    fun observeLastRuns(): Flow<Map<String, Run>>

    /**
     * The newest run of the scenario whose actions were set to run — pending, done or failed, not skipped or cancelled:
     * the pause counts from it.
     */
    suspend fun lastExecuted(scenarioId: String): Run?

    /** Deletes runs older than [olderThan] and all but the [keepNewest] newest ones; pending runs are kept. */
    suspend fun prune(olderThan: Instant, keepNewest: Int)
}
