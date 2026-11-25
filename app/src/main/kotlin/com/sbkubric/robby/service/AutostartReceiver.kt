package com.sbkubric.robby.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.core.Autostart

/** Brings [RobbyService] back after a reboot or an app update. */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Autostart.shouldStartOn(intent.action)) {
            context.appContainer.serviceLauncher.start()
        }
    }
}
