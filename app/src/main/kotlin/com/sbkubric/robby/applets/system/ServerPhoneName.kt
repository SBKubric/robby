package com.sbkubric.robby.applets.system

import android.content.Context
import androidx.core.content.edit
import com.sbkubric.robby.core.engine.DeviceNameProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `{device}`: the server phone's name as set in Robby's Settings, or else [fallback]
 * (the name from Android's settings or the model).
 */
class ServerPhoneName(context: Context, private val fallback: DeviceNameProvider) : DeviceNameProvider {

    private val prefs = context.getSharedPreferences("server_phone", Context.MODE_PRIVATE)
    private val custom = MutableStateFlow(prefs.getString(NAME, null))

    /** The name typed in Settings; `null` while the default is used. */
    val customName: StateFlow<String?> = custom.asStateFlow()

    fun defaultName(): String = fallback.deviceName()

    /** A blank [name] goes back to the default. */
    fun set(name: String) {
        val value = name.trim().takeIf { it.isNotEmpty() }
        prefs.edit { if (value == null) remove(NAME) else putString(NAME, value) }
        custom.value = value
    }

    override fun deviceName(): String = custom.value ?: defaultName()

    private companion object {
        const val NAME = "name"
    }
}
