package com.sbkubric.robby.applets.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.core.applets.SmsApplet
import com.sbkubric.robby.core.engine.Event
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Turns an incoming SMS into an `sms.in` event for the engine. A long SMS arrives as several parts
 * in one broadcast; parts from the same sender are joined into one text.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return

        val container = context.appContainer
        val time = container.clock.instant()
        val events = messages
            .groupBy { it.displayOriginatingAddress.orEmpty() }
            .map { (sender, parts) ->
                Event(
                    triggerId = SmsApplet.INCOMING,
                    values = mapOf(
                        SmsApplet.SENDER to sender,
                        SmsApplet.TEXT to parts.joinToString("") { it.displayMessageBody.orEmpty() },
                    ),
                    time = time,
                )
            }

        // Keeps the broadcast alive while the scenarios run, within the ~10 s the system allows a receiver:
        // the engine stops a run after RUN_LIMIT and logs it as timed out; BROADCAST_LIMIT is the backstop
        // that always lets finish() happen. A delivery queue in the service replaces this in slice 6.
        val pending = goAsync()
        container.appScope.launch {
            try {
                withTimeoutOrNull(BROADCAST_LIMIT) {
                    events.map { async { container.handleEvent(it, timeLimit = RUN_LIMIT) } }.awaitAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val RUN_LIMIT = 8.seconds
        val BROADCAST_LIMIT = 9.seconds
    }
}
