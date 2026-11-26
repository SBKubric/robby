package com.sbkubric.robby.applets.telegram

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the connector needs to write to the group. */
data class TelegramSettings(
    val token: String,
    val botUsername: String,
    val chatId: Long,
    val groupTitle: String,
    /** The forum topic the wizard was started from, if any. */
    val threadId: Long? = null,
)

/**
 * Keeps [TelegramSettings] in app-private preferences: not part of the scenario format,
 * so the token never goes to an export file; Android backup and device transfer skip it too (data_extraction_rules.xml).
 */
class TelegramSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("telegram_connector", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    /** `null` until the wizard has finished: the connector is «Не настроен». */
    val settings: StateFlow<TelegramSettings?> = state.asStateFlow()

    private val broken = MutableStateFlow(prefs.getString(BROKEN, null))

    /** Why Telegram rejected the settings («Сломан»); `null` while they work. Survives restarts. */
    val brokenReason: StateFlow<String?> = broken.asStateFlow()

    /**
     * Stores settings that have just sent a message (the wizard's test, or a send that followed a migration),
     * so they work: the connector is no longer broken.
     */
    fun save(settings: TelegramSettings) {
        prefs.edit {
            putString(TOKEN, settings.token)
            putString(BOT, settings.botUsername)
            putLong(CHAT_ID, settings.chatId)
            putString(TITLE, settings.groupTitle)
            if (settings.threadId != null) putLong(THREAD_ID, settings.threadId) else remove(THREAD_ID)
            remove(BROKEN)
        }
        state.value = settings
        broken.value = null
    }

    /** Forgets the token, bot and group (after an import): «Не настроен», and no longer «Сломан». */
    fun clear() {
        prefs.edit().clear().apply()
        state.value = null
        broken.value = null
    }

    fun markBroken(reason: String) {
        prefs.edit { putString(BROKEN, reason) }
        broken.value = reason
    }

    private fun load(): TelegramSettings? {
        val token = prefs.getString(TOKEN, null) ?: return null
        if (!prefs.contains(CHAT_ID)) return null
        return TelegramSettings(
            token = token,
            botUsername = prefs.getString(BOT, null).orEmpty(),
            chatId = prefs.getLong(CHAT_ID, 0),
            groupTitle = prefs.getString(TITLE, null).orEmpty(),
            threadId = if (prefs.contains(THREAD_ID)) prefs.getLong(THREAD_ID, 0) else null,
        )
    }

    private companion object {
        const val TOKEN = "token"
        const val BOT = "bot_username"
        const val CHAT_ID = "chat_id"
        const val TITLE = "group_title"
        const val THREAD_ID = "thread_id"
        const val BROKEN = "broken_reason"
    }
}
