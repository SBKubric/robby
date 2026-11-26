package com.sbkubric.robby.applets.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.applets.handleEvents
import com.sbkubric.robby.core.applets.SmsApplet
import com.sbkubric.robby.core.engine.Event

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

        handleEvents(container) { events }
    }
}
