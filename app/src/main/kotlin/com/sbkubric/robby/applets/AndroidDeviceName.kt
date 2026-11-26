package com.sbkubric.robby.applets

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.sbkubric.robby.core.engine.DeviceNameProvider

/** `{device}`: the name set in the phone's settings («О телефоне»), or the model. */
class AndroidDeviceName(private val context: Context) : DeviceNameProvider {
    override fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
