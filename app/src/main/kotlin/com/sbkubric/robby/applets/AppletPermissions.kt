package com.sbkubric.robby.applets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.sbkubric.robby.core.applets.CallApplet
import com.sbkubric.robby.core.applets.SmsApplet

/**
 * Android runtime permissions a trigger needs to receive its events, or a condition to see the phone's state.
 * Element ids are unique across the catalog, so one map serves both.
 */
object AppletPermissions {

    /** The call state broadcast, the caller's number in it, and the caller's contact name. */
    private val callTrigger = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    private val byElement = mapOf(
        SmsApplet.INCOMING to listOf(Manifest.permission.RECEIVE_SMS),
        CallApplet.INCOMING to callTrigger,
        CallApplet.MISSED to callTrigger,
        CallApplet.ACTIVE to listOf(Manifest.permission.READ_PHONE_STATE),
    )

    fun forElement(elementId: String): List<String> = byElement[elementId].orEmpty()

    fun missing(context: Context, elementId: String): List<String> =
        forElement(elementId).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
