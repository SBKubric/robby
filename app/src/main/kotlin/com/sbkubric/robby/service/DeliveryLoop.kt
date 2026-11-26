package com.sbkubric.robby.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.util.Log
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.core.engine.DeliverRuns
import com.sbkubric.robby.core.scenario.ScenarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [DeliverRuns] in [RobbyService], not WorkManager (research, issue #2): the always-on service is already there,
 * and WorkManager's own scheduling would add delays of its own.
 *
 * A pass runs when a run is recorded ([kick]), when its next attempt is due, when the network comes back
 * (`registerDefaultNetworkCallback`: waiting runs are tried at once), when the scenarios change (switched off or deleted
 * ones are cancelled) and when the service starts (runs left by a dead process or a reboot).
 *
 * The phone sleeps whenever nothing holds it awake, on the charger too, and the coroutine timer does not run while it
 * sleeps; so the next attempt is also an alarm that wakes the phone (in Doze too), exact when the user allowed exact
 * alarms. A partial wake lock is held only while a pass runs, with a time limit.
 */
class DeliveryLoop(
    context: Context,
    private val deliver: DeliverRuns,
    private val scenarios: ScenarioRepository,
    private val launcher: ServiceLauncher,
    private val clock: Clock,
) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)

    // Not counted: whoever releases it last, it is released once; the time limit is the backstop.
    private val wakeLock = this.context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Robby:delivery")
        .apply { setReferenceCounted(false) }

    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val networkReturned = AtomicBoolean(false)

    @Volatile private var job: Job? = null

    /** Called by [RobbyService] when it starts; a second call while it runs does nothing. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val network = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = kick(networkReturned = true)
            }
            connectivity.registerDefaultNetworkCallback(network)
            val scenarioChanges = launch { scenarios.observeAll().drop(1).collect { kick() } }
            try {
                loop()
            } finally {
                scenarioChanges.cancel()
                connectivity.unregisterNetworkCallback(network)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
        kick()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Asks for a pass now. Holds the phone awake until the loop takes over, so it can be called from a receiver
     * (e.g. the alarm's) whose own wake lock ends with `onReceive`. If the service is not running, starts it.
     */
    fun kick(networkReturned: Boolean = false) {
        if (networkReturned) this.networkReturned.set(true)
        wakeLock.acquire(HANDOVER_MILLIS)
        signals.trySend(Unit)
        if (job?.isActive != true) {
            try {
                launcher.start()
            } catch (e: IllegalStateException) {
                // Not allowed from the background without the battery exemption; the run waits for the next start.
                Log.w("Robby", "Cannot start the service to deliver", e)
            }
        }
    }

    private suspend fun loop() {
        var next: Instant? = null
        while (true) {
            val wait = next?.let { Duration.between(clock.instant(), it).toMillis() }
            if (wait == null) signals.receive() else withTimeoutOrNull(wait) { signals.receive() }

            wakeLock.acquire(PASS_LIMIT_MILLIS)
            next = try {
                deliver(networkReturned = networkReturned.getAndSet(false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Robby", "Delivery pass failed", e)
                clock.instant().plus(AFTER_CRASH)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
            setAlarm(next)
        }
    }

    private fun setAlarm(at: Instant?) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DeliveryAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) alarms.cancel(intent) else alarms.wakeAt(at.toEpochMilli(), intent)
    }

    private companion object {
        /** From [kick] until the loop takes its own hold. */
        const val HANDOVER_MILLIS = 30_000L

        /** A pass sends at most one message per queue that fails; a few minutes at worst. */
        const val PASS_LIMIT_MILLIS = 10 * 60_000L

        val AFTER_CRASH: Duration = Duration.ofMinutes(1)
    }
}

/** The alarm for a run's next attempt: wakes the delivery loop, starting the service if it is not running. */
class DeliveryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.appContainer.delivery.kick()
    }
}
