package com.sbkubric.robby.core

/**
 * Which system broadcasts must bring the always-on service back up.
 *
 * The values are the platform's action strings, kept as plain text so that
 * `:core` stays free of Android dependencies.
 */
object Autostart {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    fun shouldStartOn(action: String?): Boolean =
        action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED
}
