package com.sbkubric.robby.applets.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.MainActivity
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applets.SystemApplet
import com.sbkubric.robby.core.engine.ActionPerformer
import com.sbkubric.robby.core.engine.ActionResult

/** `system.notify`: a notification on the server phone, in its own channel «Уведомления сценариев». */
class ScenarioNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    /**
     * Each run's notification has its own tag, the action's key (run id and action index): it stays until swiped away,
     * is not replaced by another run's after a restart, and a repeated attempt of the same action replaces itself.
     */
    val performer = ActionPerformer { params, key ->
        if (!manager.areNotificationsEnabled()) {
            return@ActionPerformer ActionResult.Failed(this.context.getString(R.string.notify_disabled))
        }
        ensureChannel()
        val title = params[SystemApplet.P_TITLE].orEmpty()
        val text = params[SystemApplet.P_TEXT].orEmpty()
        val openApp = PendingIntent.getActivity(
            this.context,
            0,
            Intent(this.context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this.context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(TAG_PREFIX + key, NOTIFICATION_ID, notification)
        ActionResult.Done(this.context.getString(R.string.notify_shown, title))
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notify_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "scenarios"
        const val TAG_PREFIX = "run:"

        // Notifications are told apart by their tag; the id only has to differ from the service's own (1).
        const val NOTIFICATION_ID = 1000
    }
}
