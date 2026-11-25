package com.sbkubric.robby.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.appContainer

/** Brings [RobbyService] back after a reboot or an app update. */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                context.appContainer.serviceLauncher.start()
        }
    }
}
