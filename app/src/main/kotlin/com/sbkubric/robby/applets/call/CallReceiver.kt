package com.sbkubric.robby.applets.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.applets.handleEvents

/**
 * The Call applet's events: the system's `PHONE_STATE` broadcast (needs `READ_PHONE_STATE`), handed to the
 * [com.sbkubric.robby.core.applets.CallWatch]. The number comes in `EXTRA_INCOMING_NUMBER` only with `READ_CALL_LOG` too,
 * and then each state is broadcast twice, with and without it. Some OEM builds leave it out even then: the number is
 * «—», and a `CallScreeningService` would be the way to get it (not built in v0.1).
 *
 * Readings are queued here, in the order the system delivers them, and the watch takes them one by one
 * ([CallReadings]); the broadcast is kept alive until its reading is handled and its scenarios have run.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = callStateOf(intent.getStringExtra(TelephonyManager.EXTRA_STATE)) ?: return
        @Suppress("DEPRECATION") // still the only way to get the number without being the call screening app
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val numberExpected = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

        val container = context.appContainer
        val time = container.clock.instant()
        val events = container.callReadings.add(state, number, time, numberExpected)
        handleEvents(container) { events.await() }
    }
}
