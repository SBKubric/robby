package com.sbkubric.robby.applets.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applets.CallWatch
import com.sbkubric.robby.core.applets.CallWatchStore
import com.sbkubric.robby.core.engine.CallStateProvider
import com.sbkubric.robby.core.engine.ContactNames
import kotlinx.serialization.json.Json

/** The `EXTRA_STATE` of a `PHONE_STATE` broadcast as a [CallState]; `null` if unknown. */
fun callStateOf(extraState: String?): CallState? = when (extraState) {
    TelephonyManager.EXTRA_STATE_IDLE -> CallState.IDLE
    TelephonyManager.EXTRA_STATE_RINGING -> CallState.RINGING
    TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.OFFHOOK
    else -> null
}

private fun Context.has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** The phone's calls for conditions, from `TelephonyManager`; `null` without `READ_PHONE_STATE`. */
class TelephonyCallState(private val context: Context) : CallStateProvider {
    override fun callState(): CallState? {
        if (!context.has(Manifest.permission.READ_PHONE_STATE)) return null
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        // Deprecated for the per-subscription call, but this one covers the calls of every SIM, which is what we want.
        @Suppress("DEPRECATION")
        val state = runCatching { telephony.callState }.getOrNull() ?: return null
        return when (state) {
            TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> null
        }
    }
}

/** Contact names from the phone's contacts; none without `READ_CONTACTS`, which is not a failure. */
class AndroidContactNames(private val context: Context) : ContactNames {
    override fun nameOf(number: String): String? {
        if (!context.has(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}

/** Keeps the [CallWatch]'s ringing call in app-private preferences, written at once: the next broadcast may start a new process. */
class CallWatchPrefs(context: Context) : CallWatchStore {

    private val prefs = context.getSharedPreferences("call_watch", Context.MODE_PRIVATE)

    override fun load(): CallWatch.Ringing? =
        prefs.getString(RINGING, null)?.let { runCatching { Json.decodeFromString(CallWatch.Ringing.serializer(), it) }.getOrNull() }

    override fun save(ringing: CallWatch.Ringing?) {
        prefs.edit(commit = true) {
            if (ringing == null) remove(RINGING) else putString(RINGING, Json.encodeToString(CallWatch.Ringing.serializer(), ringing))
        }
    }

    private companion object {
        const val RINGING = "ringing"
    }
}
