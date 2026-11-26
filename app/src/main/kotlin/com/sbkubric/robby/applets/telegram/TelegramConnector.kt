package com.sbkubric.robby.applets.telegram

import android.content.Context
import android.os.SystemClock
import com.sbkubric.robby.R
import com.sbkubric.robby.core.applet.ConnectorState
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.core.engine.ActionPerformer
import com.sbkubric.robby.core.engine.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * The Telegram connector: its state, sending to the configured group, and the `telegram.send` performer.
 *
 * Sends are spaced at least [MIN_INTERVAL_MILLIS] apart: a group takes about 20 messages a minute. The limit is
 * Telegram's, so it lives here rather than in the delivery queue, which already sends one message at a time per
 * connector; a 429 that slips through still comes back as a retry after Telegram's `retry_after`.
 */
class TelegramConnector(
    private val api: TelegramApi,
    private val store: TelegramSettingsStore,
    context: Context,
) {
    private val resources = context.applicationContext.resources

    val settings = store.settings

    /** Why Telegram rejected the bot (token revoked, bot removed from the group); `null` while it works. */
    val brokenReason: StateFlow<String?> = store.brokenReason

    val state: Flow<ConnectorState> = combine(store.settings, store.brokenReason) { settings, broken ->
        when {
            settings == null -> ConnectorState.NOT_CONFIGURED
            broken != null -> ConnectorState.BROKEN
            else -> ConnectorState.WORKING
        }
    }

    /** Stores the settings the wizard has checked: the connector becomes «Работает», also if it was broken. */
    fun save(settings: TelegramSettings) = store.save(settings)

    fun markBroken(reason: String) = store.markBroken(reason)

    /** Performs `telegram.send` with the stored settings. */
    val sendPerformer = ActionPerformer { params, _ ->
        val settings = store.settings.value
            ?: return@ActionPerformer ActionResult.Failed(resources.getString(R.string.telegram_not_configured))
        when (val result = send(settings, params[TelegramApplet.TEXT].orEmpty())) {
            is SendResult.Sent -> {
                if (result.settings != settings) store.save(result.settings)
                ActionResult.Done(resources.getString(R.string.telegram_delivered, result.settings.groupTitle))
            }
            is SendResult.Failed -> result.problem
        }
    }

    sealed interface SendResult {
        /** [settings] carry the new chat id if the group became a supergroup on the way. */
        data class Sent(val settings: TelegramSettings) : SendResult

        /** Why not, and whether to retry or treat the connector as broken. */
        data class Failed(val problem: ActionResult.Problem) : SendResult
    }

    private val sending = Mutex()
    private var lastSentAt = Long.MIN_VALUE / 2

    /** Sends plain [text] to the group, following a migration to a supergroup once. */
    suspend fun send(settings: TelegramSettings, text: String): SendResult = sending.withLock {
        val message = text.limitTo(MAX_LENGTH)
        var current = settings
        repeat(2) {
            delay(lastSentAt + MIN_INTERVAL_MILLIS - SystemClock.elapsedRealtime())
            val response = api.sendMessage(current.token, current.chatId, current.threadId, message)
            lastSentAt = SystemClock.elapsedRealtime()
            when (response) {
                is TelegramApi.Response.Ok -> return SendResult.Sent(current)
                is TelegramApi.Response.Error -> {
                    val migrated = response.migrateToChatId
                    if (migrated == null || migrated == current.chatId) return SendResult.Failed(problemOf(response))
                    current = current.copy(chatId = migrated)
                }
                is TelegramApi.Response.NetworkError -> return SendResult.Failed(
                    ActionResult.Retry(resources.getString(R.string.telegram_network_error, response.message)),
                )
            }
        }
        SendResult.Failed(ActionResult.Failed(resources.getString(R.string.telegram_error, "migration loop")))
    }

    /**
     * Telegram's error codes (research, issue #3): 429 and 5xx are worth retrying; 401 (token revoked), 403 (bot kicked
     * from or not a member of the group), a bare 404 (malformed token) and a group or topic that is gone will fail every
     * message until the bot is set up again, so the connector is broken; any other 4xx fails just this message.
     */
    private fun problemOf(error: TelegramApi.Response.Error): ActionResult.Problem {
        val reason = describe(error)
        val text = error.description.lowercase()
        return when {
            error.httpCode == 429 -> ActionResult.Retry(reason, error.retryAfterSeconds?.let(Duration::ofSeconds))
            error.httpCode >= 500 -> ActionResult.Retry(reason)
            error.httpCode in setOf(401, 403, 404) -> ActionResult.Broken(reason)
            "chat not found" in text || "thread not found" in text -> ActionResult.Broken(reason)
            else -> ActionResult.Failed(reason)
        }
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

        /** About 20 messages a minute to one group. */
        const val MIN_INTERVAL_MILLIS = 3_000L
    }
}
