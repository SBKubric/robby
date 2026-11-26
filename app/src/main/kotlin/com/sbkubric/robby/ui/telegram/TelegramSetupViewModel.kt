package com.sbkubric.robby.ui.telegram

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbkubric.robby.R
import com.sbkubric.robby.applets.telegram.FoundGroup
import com.sbkubric.robby.applets.telegram.TelegramApi
import com.sbkubric.robby.applets.telegram.TelegramConnector
import com.sbkubric.robby.applets.telegram.TelegramSettings
import com.sbkubric.robby.applets.telegram.asArray
import com.sbkubric.robby.applets.telegram.botUsername
import com.sbkubric.robby.applets.telegram.findGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wizard: token → `getMe` → `startgroup` link while `getUpdates` waits for the group → test message,
 * after which the settings are saved. Polling runs only while the wizard is open (its ViewModel lives).
 */
class TelegramSetupViewModel(
    private val api: TelegramApi,
    private val connector: TelegramConnector,
    private val resources: Resources,
) : ViewModel() {

    var token by mutableStateOf(connector.settings.value?.token.orEmpty())
        private set
    var checking by mutableStateOf(false)
        private set
    var bot by mutableStateOf<String?>(null)
        private set
    var tokenError by mutableStateOf<String?>(null)
        private set

    /** Lets the wizard recognise the `/start@bot <nonce>` its own link produces. */
    private val nonce = (1..16).map { NONCE_CHARS.random() }.joinToString("")
    val link: String? get() = bot?.let { "https://t.me/$it?startgroup=$nonce" }

    var waiting by mutableStateOf(false)
        private set
    var group by mutableStateOf<FoundGroup?>(null)
        private set
    var groupError by mutableStateOf<String?>(null)
        private set

    var sending by mutableStateOf(false)
        private set
    var testError by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    private var search: Job? = null

    fun onTokenChange(value: String) {
        token = value.trim()
        bot = null
        tokenError = null
        search?.cancel()
        waiting = false
        group = null
        groupError = null
        testError = null
        done = false
    }

    fun checkToken() {
        if (!TOKEN_FORMAT.matches(token)) {
            tokenError = resources.getString(R.string.tg_token_format)
            return
        }
        checking = true
        tokenError = null
        viewModelScope.launch {
            when (val response = api.getMe(token)) {
                is TelegramApi.Response.Ok -> {
                    bot = botUsername(response.result).orEmpty()
                    startSearch()
                }
                is TelegramApi.Response.Error -> tokenError =
                    if (response.httpCode == 401 || response.httpCode == 404) {
                        resources.getString(R.string.tg_token_rejected)
                    } else {
                        connector.describe(response)
                    }
                is TelegramApi.Response.NetworkError ->
                    tokenError = resources.getString(R.string.telegram_network_error, response.message)
            }
            checking = false
        }
    }

    fun startSearch() {
        search?.cancel()
        group = null
        groupError = null
        waiting = true
        search = viewModelScope.launch {
            waitForGroup()
            waiting = false
        }
    }

    private suspend fun waitForGroup() {
        val deadline = System.currentTimeMillis() + SEARCH_MILLIS
        var offset: Long? = null
        while (System.currentTimeMillis() < deadline) {
            when (val response = api.getUpdates(token, offset, POLL_SECONDS)) {
                is TelegramApi.Response.Ok -> {
                    val (found, next) = findGroup(response.result.asArray(), nonce)
                    if (next != null) offset = next
                    if (found != null) {
                        // Confirms the updates read so far, so they are not delivered again.
                        if (offset != null) api.getUpdates(token, offset, 0)
                        group = found
                        return
                    }
                }
                is TelegramApi.Response.Error -> {
                    // E.g. 409: a webhook is set or another app polls this bot.
                    groupError = connector.describe(response)
                    return
                }
                is TelegramApi.Response.NetworkError -> delay(RETRY_MILLIS)
            }
        }
        groupError = resources.getString(R.string.tg_group_timeout)
    }

    fun sendTest() {
        val found = group ?: return
        val settings = TelegramSettings(token, bot.orEmpty(), found.chatId, found.title, found.threadId)
        sending = true
        testError = null
        viewModelScope.launch {
            when (val result = connector.send(settings, resources.getString(R.string.tg_test_message))) {
                is TelegramConnector.SendResult.Sent -> {
                    connector.save(result.settings)
                    done = true
                }
                is TelegramConnector.SendResult.Failed -> testError = result.reason
            }
            sending = false
        }
    }

    private companion object {
        val TOKEN_FORMAT = Regex("""\d+:[A-Za-z0-9_-]+""")
        val NONCE_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        const val POLL_SECONDS = 25
        const val SEARCH_MILLIS = 5 * 60 * 1000L
        const val RETRY_MILLIS = 3000L
    }
}
