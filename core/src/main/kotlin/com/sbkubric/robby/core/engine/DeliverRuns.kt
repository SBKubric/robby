package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunAction
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Delivery: performs the pending runs [HandleEvent] recorded, from their snapshots, and moves each one on.
 *
 * - **Queues.** A run that uses a connector waits in that connector's queue (the first connector among its actions),
 *   local actions and all; a run with only local actions (a notification) waits in the local queue. Each queue goes
 *   strictly in event order: a run waiting to retry holds back the runs behind it («порядок сохраняется»), while the
 *   queues go side by side, so no network wait holds back a local run.
 * - **Actions** of a run go in order; one already done is not performed again when the run is retried.
 * - **Temporary failure** ([ActionResult.Retry]): the run waits, 30 s after the first failure, doubling up to 15 min,
 *   never less than the service asked for; a run still failing 24 h after its event is «не выполнено».
 * - **Permanent failure** ([ActionResult.Failed]): the run fails. [ActionResult.Broken] also marks its connector
 *   «Сломан», and then every run that needs that connector fails at once instead of waiting.
 * - **Late**: a message performed more than [LateMark.THRESHOLD] after its event gets «⏱ HH:MM» of the event.
 * - **Cancelled**: a waiting run whose scenario has been switched off or deleted is not performed.
 */
class DeliverRuns(
    private val log: ActivityLog,
    private val scenarios: ScenarioRepository,
    private val catalog: AppletCatalog,
    /** Action performers by action id. */
    private val performers: Map<String, ActionPerformer>,
    private val connectors: ConnectorHealth,
    private val clock: Clock,
) {
    // One pass at a time: two would perform the same run twice.
    private val passing = Mutex()

    /**
     * One pass over the pending runs. With [networkReturned], runs waiting after a temporary failure are tried at once.
     * Returns when the next pass is due (the earliest next attempt), or `null` if nothing waits.
     */
    suspend operator fun invoke(networkReturned: Boolean = false): Instant? = passing.withLock {
        val queues = withoutCancelled(log.pending()).groupBy { queueOf(it) }.values
        coroutineScope { queues.map { queue -> async { deliver(queue, networkReturned) } }.awaitAll() }
            .filterNotNull()
            .minOrNull()
    }

    /** Cancels the runs whose scenario is switched off or gone; returns the others. */
    private suspend fun withoutCancelled(pending: List<Run>): List<Run> {
        if (pending.isEmpty()) return pending
        val current = scenarios.all().associateBy { it.id }
        return pending.filter { run ->
            val reason = when (current[run.scenarioId]?.enabled) {
                null -> RunReason.SCENARIO_DELETED
                false -> RunReason.SCENARIO_DISABLED
                true -> null
            }
            if (reason != null) log.update(run.finish(RunStatus.CANCELLED, null, reason))
            reason == null
        }
    }

    /** Delivers one queue in order up to the first run that has to wait; returns when that one is due. */
    private suspend fun deliver(queue: List<Run>, networkReturned: Boolean): Instant? {
        for (run in queue) {
            val due = run.nextAttemptAt
            if (!networkReturned && due != null && due.isAfter(clock.instant())) return due
            val result = attempt(run)
            log.update(result)
            if (result.status == RunStatus.PENDING) return result.nextAttemptAt
        }
        return null
    }

    private suspend fun attempt(run: Run): Run {
        var current = run
        for ((index, action) in run.actions.withIndex()) {
            if (action.done) continue
            val connector = connectorOf(action.id)
            connector?.let { connectors.brokenReason(it) }?.let { reason ->
                return current.finish(RunStatus.FAILED, reason, RunReason.CONNECTOR_BROKEN)
            }
            val performer = performers[action.id] ?: return current.finish(RunStatus.FAILED, action.id, RunReason.NO_PERFORMER)
            val late = LateMark.isLate(run.time, clock.instant())
            when (val result = perform(performer, paramsOf(action, run, late), "${run.id}.$index")) {
                is ActionResult.Done -> current = current.copy(
                    actions = current.actions.toMutableList().also { it[index] = action.copy(done = true, note = result.note) },
                    late = current.late || (late && messageParamOf(action) != null),
                )
                is ActionResult.Retry -> return current.retry(result)
                is ActionResult.Failed -> return current.finish(RunStatus.FAILED, result.reason)
                is ActionResult.Broken -> {
                    connectors.markBroken(connector ?: catalog.appletOf(action.id)?.id ?: action.id, result.reason)
                    return current.finish(RunStatus.FAILED, result.reason, RunReason.CONNECTOR_BROKEN)
                }
            }
        }
        return current.finish(RunStatus.DONE, current.actions.mapNotNull { it.note }.joinToString("; ").ifEmpty { null })
    }

    /** The snapshot's params, the message marked «⏱ HH:MM» if it is [late]. */
    private fun paramsOf(action: RunAction, run: Run, late: Boolean): Map<String, String> {
        val key = messageParamOf(action) ?: return action.params
        if (!late) return action.params
        return action.params.mapValues { (k, v) -> if (k == key) LateMark.mark(v, run.time, clock.zone) else v }
    }

    private fun messageParamOf(action: RunAction): String? =
        catalog.action(action.id)?.messageParam?.takeIf { it in action.params }

    /** The connector (an applet id, e.g. `telegram`) the action needs; `null` for a local action. */
    private fun connectorOf(actionId: String): String? = catalog.appletOf(actionId)?.takeIf { it.isConnector }?.id

    /** The queue a run waits in: its first connector, or `null` — the local queue. */
    private fun queueOf(run: Run): String? = run.actions.firstNotNullOfOrNull { connectorOf(it.id) }

    /** Waits before the next attempt, longer each time, or gives up 24 h after the event. */
    private fun Run.retry(result: ActionResult.Retry): Run {
        val now = clock.instant()
        val deadline = time + GIVE_UP_AFTER
        if (!now.isBefore(deadline)) return finish(RunStatus.FAILED, result.reason, RunReason.GAVE_UP)
        val failures = attempts + 1
        val delay = maxOf(backoff(failures), result.after ?: Duration.ZERO)
        return copy(attempts = failures, nextAttemptAt = minOf(now + delay, deadline), message = result.reason)
    }

    private suspend fun perform(performer: ActionPerformer, params: Map<String, String>, key: String): ActionResult =
        try {
            performer.perform(params, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failed(e.message ?: e.javaClass.simpleName)
        }

    private fun Run.finish(status: RunStatus, message: String?, reason: RunReason? = null) =
        copy(status = status, message = message, reason = reason, nextAttemptAt = null)

    companion object {
        /** A run that keeps failing temporarily is «не выполнено» this long after its event. */
        val GIVE_UP_AFTER: Duration = Duration.ofHours(24)

        private val FIRST_DELAY: Duration = Duration.ofSeconds(30)
        private val MAX_DELAY: Duration = Duration.ofMinutes(15)

        /** 30 s after the first failure, doubling, at most 15 min. */
        private fun backoff(failures: Int): Duration {
            val doublings = (failures - 1).coerceIn(0, 10)
            return minOf(FIRST_DELAY.multipliedBy(1L shl doublings), MAX_DELAY)
        }
    }
}
