package com.sbkubric.robby.service

import android.content.Context
import android.content.Intent

/** Starts [RobbyService]; safe to call repeatedly. */
class ServiceLauncher(private val context: Context) {
    fun start() {
        context.startForegroundService(Intent(context, RobbyService::class.java))
    }
}
