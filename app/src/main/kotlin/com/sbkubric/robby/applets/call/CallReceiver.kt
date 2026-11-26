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
 * Broadcasts are handled side by side; the two of a pair may be handled in either order, which the watch allows for.
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
        handleEvents(container) { container.callWatch.onReading(state, number, time, numberExpected) }
    }
}
