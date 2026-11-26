package com.sbkubric.robby.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sbkubric.robby.MainActivity
import com.sbkubric.robby.R

/**
 * The ongoing notification «Telegram сломан» on the server phone, shown while the connector is broken
 * and removed once it works again; [RobbyService] follows the connector's state.
 */
class BrokenConnectorAlert(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    /** Shows the alert for the connector [name] with the [reason], or removes it if [reason] is `null`. */
    fun update(name: String, reason: String?) {
        if (reason == null) {
            manager.cancel(TAG_PREFIX + name, NOTIFICATION_ID)
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.broken_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.broken_channel_description) }
        manager.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(R.string.broken_notification_text, reason)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.broken_notification_title, name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        manager.notify(TAG_PREFIX + name, NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "connectors"
        const val TAG_PREFIX = "connector:"
        const val NOTIFICATION_ID = 2
    }
}
