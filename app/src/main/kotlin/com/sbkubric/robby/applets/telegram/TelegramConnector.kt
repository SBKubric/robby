package com.sbkubric.robby.applets.telegram

import android.content.Context
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.core.engine.ActionPerformer
import com.sbkubric.robby.core.engine.ActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The Telegram connector: its state, sending to the configured group, and the `telegram.send` performer. */
class TelegramConnector(
    private val api: TelegramApi,
    private val store: TelegramSettingsStore,
    context: Context,
) {
    private val resources = context.applicationContext.resources

    val settings = store.settings

    // «Сломан» needs the delivery work of slice 6; until then a configured connector counts as working.
    val state: Flow<ConnectorState> =
        store.settings.map { if (it == null) ConnectorState.NOT_CONFIGURED else ConnectorState.WORKING }

    /** Stores the settings the wizard has checked: the connector becomes «Работает». */
    fun save(settings: TelegramSettings) = store.save(settings)

    /** Performs `telegram.send` with the stored settings. */
    val sendPerformer = ActionPerformer { params ->
        val settings = store.settings.value
            ?: return@ActionPerformer ActionResult.Failed(resources.getString(R.string.telegram_not_configured))
        when (val result = send(settings, params[TelegramApplet.TEXT].orEmpty())) {
            is SendResult.Sent -> {
                if (result.settings != settings) store.save(result.settings)
                ActionResult.Done(resources.getString(R.string.telegram_delivered, result.settings.groupTitle))
            }
            is SendResult.Failed -> ActionResult.Failed(result.reason)
        }
    }

    sealed interface SendResult {
        /** [settings] carry the new chat id if the group became a supergroup on the way. */
        data class Sent(val settings: TelegramSettings) : SendResult
        data class Failed(val reason: String) : SendResult
    }

    /** Sends plain [text] to the group, following a migration to a supergroup once. */
    suspend fun send(settings: TelegramSettings, text: String): SendResult {
        val message = text.limitTo(MAX_LENGTH)
        var current = settings
        repeat(2) {
            when (val response = api.sendMessage(current.token, current.chatId, current.threadId, message)) {
                is TelegramApi.Response.Ok -> return SendResult.Sent(current)
                is TelegramApi.Response.Error -> {
                    val migrated = response.migrateToChatId
                    if (migrated == null || migrated == current.chatId) return SendResult.Failed(describe(response))
                    current = current.copy(chatId = migrated)
                }
                is TelegramApi.Response.NetworkError ->
                    return SendResult.Failed(resources.getString(R.string.telegram_network_error, response.message))
            }
        }
        return SendResult.Failed(resources.getString(R.string.telegram_error, "migration loop"))
    }

    fun describe(error: TelegramApi.Response.Error): String = resources.getString(R.string.telegram_error, error.description)

    private fun String.limitTo(max: Int): String {
        if (length <= max) return this
        val end = if (this[max - 2].isHighSurrogate()) max - 2 else max - 1
        return substring(0, end) + "…"
    }

    private companion object {
        /** Telegram's limit for one message. */
        const val MAX_LENGTH = 4096
    }
}
