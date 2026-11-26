package com.sbkubric.robby.applets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.sbkubric.robby.core.applets.SmsApplet

/** Android runtime permissions a trigger needs to receive its events. */
object AppletPermissions {

    private val byTrigger = mapOf(
        SmsApplet.INCOMING to listOf(Manifest.permission.RECEIVE_SMS),
    )

    fun forTrigger(triggerId: String): List<String> = byTrigger[triggerId].orEmpty()

    fun missing(context: Context, triggerId: String): List<String> =
        forTrigger(triggerId).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
