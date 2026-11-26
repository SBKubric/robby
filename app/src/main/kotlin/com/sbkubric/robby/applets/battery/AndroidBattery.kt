package com.sbkubric.robby.applets.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.edit
import com.sbkubric.robby.core.applet.BatteryState
import com.sbkubric.robby.core.applets.BatteryWatch
import com.sbkubric.robby.core.applets.BatteryWatchStore
import com.sbkubric.robby.core.engine.BatteryStateProvider
import kotlinx.serialization.json.Json

/** The reading in an `ACTION_BATTERY_CHANGED` intent; `null` if it has no level. Temperature comes in tenths of °C. */
fun Intent.toBatteryState(): BatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || scale <= 0) return null
    return BatteryState(
        level = level * 100 / scale,
        temperature = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
        plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
    )
}

/** The battery for conditions: the last `ACTION_BATTERY_CHANGED`, which the system keeps (a sticky broadcast). */
class StickyBatteryState(private val context: Context) : BatteryStateProvider {
    override fun batteryState(): BatteryState? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.toBatteryState()
}

/** Keeps the [BatteryWatch]'s memory in app-private preferences, written at once: the process may die any moment. */
class BatteryWatchPrefs(context: Context) : BatteryWatchStore {

    private val prefs = context.getSharedPreferences("battery_watch", Context.MODE_PRIVATE)

    override fun load(): BatteryWatch.Memory? =
        prefs.getString(MEMORY, null)?.let { runCatching { Json.decodeFromString(BatteryWatch.Memory.serializer(), it) }.getOrNull() }

    override fun save(memory: BatteryWatch.Memory) {
        prefs.edit(commit = true) { putString(MEMORY, Json.encodeToString(BatteryWatch.Memory.serializer(), memory)) }
    }

    private companion object {
        const val MEMORY = "memory"
    }
}
