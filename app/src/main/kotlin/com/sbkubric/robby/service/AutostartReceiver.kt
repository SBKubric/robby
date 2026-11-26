package com.sbkubric.robby.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.appContainer
import com.sbkubric.robby.applets.handleEvents
import com.sbkubric.robby.core.applets.SystemApplet
import com.sbkubric.robby.core.engine.Event

/**
 * Brings [RobbyService] back and sets the schedule alarm again after a reboot or an app update;
 * a reboot is also the `system.boot` event («Телефон-сервер включился»).
 */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                container.serviceLauncher.start()
                handleEvents(container) {
                    container.scheduleAlarms.reschedule()
                    listOf(Event(SystemApplet.BOOT, emptyMap(), container.clock.instant()))
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                container.serviceLauncher.start()
                handleEvents(container) {
                    container.scheduleAlarms.reschedule()
                    emptyList()
                }
            }
        }
    }
}
