package com.sbkubric.robby.applets.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The few Telegram Bot API calls Robby makes, over plain [HttpURLConnection]: no client library is needed
 * for three JSON POSTs. The token is part of the URL, so it never goes into messages or logs.
 */
class TelegramApi(private val baseUrl: String = "https://api.telegram.org") {

    sealed interface Response {
        data class Ok(val result: JsonElement) : Response

        /** Telegram answered `ok: false`; [description] is shown to the user as is. */
        data class Error(val httpCode: Int, val description: String, val migrateToChatId: Long?) : Response

        /** No answer: no network, timeout, or an unreadable reply. */
        data class NetworkError(val message: String) : Response
    }

    /** `getMe`: the bot's username if the token is valid. */
    suspend fun getMe(token: String): Response = call(token, "getMe", JsonObject(emptyMap()))

    /** Long polling `getUpdates`; used only by the setup wizard while it is open. */
    suspend fun getUpdates(token: String, offset: Long?, timeoutSeconds: Int): Response =
        call(
            token,
            "getUpdates",
            buildJsonObject {
                if (offset != null) put("offset", offset)
                put("timeout", timeoutSeconds)
            },
            readTimeoutMillis = (timeoutSeconds + 15) * 1000,
        )

    /** Plain text `sendMessage` (no parse_mode, so nothing needs escaping), without link previews. */
    suspend fun sendMessage(token: String, chatId: Long, threadId: Long?, text: String): Response =
        call(
            token,
            "sendMessage",
            buildJsonObject {
                put("chat_id", chatId)
                if (threadId != null) put("message_thread_id", threadId)
                put("text", text)
                putJsonObject("link_preview_options") { put("is_disabled", true) }
            },
            // Short, so that one attempt fits the SMS receiver's time (slice 6 moves delivery to a queue).
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 5_000,
        )

    private suspend fun call(
        token: String,
        method: String,
        body: JsonObject,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 20_000,
    ): Response {
        val connection = URL("$baseUrl/bot$token/$method").openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        return suspendCancellableCoroutine { continuation ->
            // Blocking socket I/O ignores coroutine cancellation: closing the connection is what stops it,
            // so a caller's time limit (e.g. the SMS receiver's) is kept even in the middle of a request.
            continuation.invokeOnCancellation { connection.disconnect() }
            Dispatchers.IO.asExecutor().execute {
                continuation.resumeWith(runCatching { exchange(connection, token, body) })
            }
        }
    }

    private fun exchange(connection: HttpURLConnection, token: String, body: JsonObject): Response =
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            parse(code, text)
        } catch (e: IOException) {
            Response.NetworkError((e.message ?: e.javaClass.simpleName).replace(token, "***"))
        } finally {
            connection.disconnect()
        }

    private fun parse(code: Int, text: String): Response {
        val reply = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return if (code == 404) {
                // The cloud Bot API answers a malformed token with a bare 404.
                Response.Error(code, "Not Found", null)
            } else {
                Response.NetworkError("HTTP $code")
            }
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull == true) return Response.Ok(reply["result"] ?: JsonPrimitive(true))
        val parameters = reply["parameters"] as? JsonObject
        return Response.Error(
            httpCode = code,
            description = reply["description"]?.jsonPrimitive?.contentOrNull ?: "HTTP $code",
            migrateToChatId = parameters?.get("migrate_to_chat_id")?.jsonPrimitive?.longOrNull,
        )
    }
}

/** The group the bot was added to through the wizard's `startgroup` link. */
data class FoundGroup(val chatId: Long, val title: String, val threadId: Long?)

/**
 * Looks through `getUpdates` results for `/start@bot <nonce>` (sent by the `startgroup` link) and returns the group,
 * following a group-to-supergroup migration if one appears in the same batch, plus the next offset to ask for.
 */
fun findGroup(updates: JsonArray, nonce: String): Pair<FoundGroup?, Long?> {
    var found: FoundGroup? = null
    var nextOffset: Long? = null
    for (update in updates.map { it.jsonObject }) {
        update["update_id"]?.jsonPrimitive?.longOrNull?.let { nextOffset = maxOf(nextOffset ?: 0, it + 1) }
        val message = update["message"] as? JsonObject ?: continue
        val chat = message["chat"] as? JsonObject ?: continue
        val chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: continue

        val migratedTo = message["migrate_to_chat_id"]?.jsonPrimitive?.longOrNull
        if (migratedTo != null && found?.chatId == chatId) found = found.copy(chatId = migratedTo)

        val words = message["text"]?.jsonPrimitive?.contentOrNull?.trim()?.split(Regex("\\s+")).orEmpty()
        if (words.size == 2 && words[0].substringBefore('@') == "/start" && words[1] == nonce) {
            val inTopic = message["is_topic_message"]?.jsonPrimitive?.booleanOrNull == true
            found = FoundGroup(
                chatId = chatId,
                title = chat["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                threadId = if (inTopic) message["message_thread_id"]?.jsonPrimitive?.longOrNull else null,
            )
        }
    }
    return found to nextOffset
}

/** The `username` from a `getMe` result. */
fun botUsername(result: JsonElement): String? = (result as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull

internal fun JsonElement.asArray(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
