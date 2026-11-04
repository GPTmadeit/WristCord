package com.wristcord.discord.rest

import com.wristcord.discord.ClientIdentity
import com.wristcord.discord.Discord
import com.wristcord.discord.Snowflake
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.ceil

class DiscordHttpException(
    val status: Int,
    /** Discord's own error code from the JSON body, when there is one. */
    val code: Int?,
    message: String,
) : IOException(message)

/**
 * The REST calls WristCord makes, with the headers Discord's Android client sends
 * (prepareRequest in the app: User-Agent, X-Super-Properties, X-Discord-Locale,
 * X-Discord-Timezone). 429s are waited out using Discord's `retry_after`.
 */
class DiscordRest(
    private val http: OkHttpClient,
    private val identity: ClientIdentity,
    private val token: () -> String?,
) {
    private val jsonType = "application/json".toMediaType()

    suspend fun me(): User = DiscordJson.decodeFromJsonElement(User.serializer(), call("GET", "/users/@me"))

    suspend fun messages(channelId: Long, before: Long? = null, limit: Int = 30): List<Message> {
        val q = buildString {
            append("?limit=").append(limit)
            if (before != null) append("&before=").append(before)
        }
        val body = call("GET", "/channels/$channelId/messages$q")
        return DiscordJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(Message.serializer()), body
        )
    }

    suspend fun sendMessage(
        channelId: Long,
        content: String,
        replyTo: Long? = null,
        guildId: Long? = null,
        nonce: String = Snowflake.nonce(),
    ): Message {
        val body = buildJsonObject {
            put("content", content)
            put("nonce", nonce)
            put("tts", false)
            put("flags", 0)
            put("mobile_network_type", "unknown")
            if (replyTo != null) {
                putJsonObject("message_reference") {
                    put("message_id", replyTo.toString())
                    put("channel_id", channelId.toString())
                    if (guildId != null) put("guild_id", guildId.toString())
                    put("fail_if_not_exists", false)
                }
            }
        }
        return DiscordJson.decodeFromJsonElement(Message.serializer(), call("POST", "/channels/$channelId/messages", body))
    }

    suspend fun typing(channelId: Long) {
        call("POST", "/channels/$channelId/typing", JsonObject(emptyMap()))
    }

    private var ackToken: String? = null

    /** Marks [channelId] read up to [messageId] - on every device signed in to the account. */
    suspend fun ack(channelId: Long, messageId: Long, guildChannel: Boolean, thread: Boolean = false) {
        val body = buildJsonObject {
            put("token", ackToken?.let(::JsonPrimitive) ?: JsonNull)
            put("last_viewed", lastViewed())
            put("flags", (if (guildChannel) 1 else 0) or (if (thread) 2 else 0))
        }
        val res = call("POST", "/channels/$channelId/messages/$messageId/ack", body)
        ackToken = (res as? JsonObject)?.get("token")?.let { (it as? JsonPrimitive)?.content }
    }

    suspend fun addReaction(channelId: Long, messageId: Long, emoji: Emoji) {
        call("PUT", "/channels/$channelId/messages/$messageId/reactions/${emojiPath(emoji)}/@me?location=Message&type=0")
    }

    suspend fun removeReaction(channelId: Long, messageId: Long, emoji: Emoji) {
        call("DELETE", "/channels/$channelId/messages/$messageId/reactions/${emojiPath(emoji)}/0/@me?location=Message")
    }

    /** Returns the base64 proto. Type 1 = PreloadedUserSettings, 2 = FrecencyUserSettings. */
    suspend fun settingsProto(type: Int): String {
        val res = call("GET", "/users/@me/settings-proto/$type").jsonObject
        return (res["settings"] as? JsonPrimitive)?.content ?: ""
    }

    /**
     * Sends a partial settings proto; Discord merges it and answers with the full result. This
     * is how a status change made on the watch reaches every other device.
     */
    suspend fun patchSettingsProto(type: Int, base64: String): String {
        val res = call("PATCH", "/users/@me/settings-proto/$type", buildJsonObject { put("settings", base64) }).jsonObject
        return (res["settings"] as? JsonPrimitive)?.content ?: ""
    }

    /** QR login: trade the ticket the phone approved for the encrypted token. */
    suspend fun remoteAuthLogin(ticket: String): String {
        val res = call("POST", "/users/@me/remote-auth/login", buildJsonObject { put("ticket", ticket) }, auth = false).jsonObject
        return (res["encrypted_token"] as? JsonPrimitive)?.content
            ?: throw DiscordHttpException(200, null, "Discord did not return a token")
    }

    /** Invalidates the token server-side, so signing out of the watch really signs it out. */
    suspend fun logout() {
        call("POST", "/auth/logout", buildJsonObject {
            put("provider", JsonNull)
            put("voip_provider", JsonNull)
        })
    }

    private fun emojiPath(emoji: Emoji): String {
        val raw = if (emoji.id != null) "${emoji.name ?: "_"}:${emoji.id}" else emoji.name.orEmpty()
        return URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
    }

    /** Days since Discord's epoch, rounded up - the unit the read-state store keeps. */
    private fun lastViewed(): Int =
        ceil((System.currentTimeMillis() - Snowflake.EPOCH_MS) / 86_400_000.0).toInt()

    private suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        auth: Boolean = true,
    ): JsonElement = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(Discord.API_BASE + path)
                .method(method, bodyFor(method, body))
                .header("User-Agent", Discord.USER_AGENT)
                .header("X-Super-Properties", identity.superPropertiesBase64)
                .header("X-Discord-Locale", identity.systemLocale)
                .header("X-Discord-Timezone", identity.timezone)
                .header("Accept-Language", identity.systemLocale)
                .apply {
                    if (auth) token()?.let { header("Authorization", it) }
                }
                .build()
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                if (res.code == 429 && attempt < MAX_RETRIES) {
                    val wait = runCatching {
                        (DiscordJson.parseToJsonElement(text).jsonObject["retry_after"] as JsonPrimitive).doubleOrNull
                    }.getOrNull() ?: 1.0
                    attempt++
                    delay((wait * 1000).toLong().coerceIn(250, 30_000))
                    return@use null
                }
                if (!res.isSuccessful) {
                    val parsed = runCatching { DiscordJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    val code = (parsed?.get("code") as? JsonPrimitive)?.intOrNull
                    val msg = (parsed?.get("message") as? JsonPrimitive)?.content
                    throw DiscordHttpException(res.code, code, msg ?: "HTTP ${res.code}")
                }
                if (text.isBlank()) JsonNull else DiscordJson.parseToJsonElement(text)
            }?.let { return@withContext it }
        }
        @Suppress("UNREACHABLE_CODE")
        JsonNull
    }

    private fun bodyFor(method: String, body: JsonElement?): RequestBody? = when {
        body != null -> body.toString().toRequestBody(jsonType)
        method == "GET" || method == "DELETE" -> null
        else -> ByteArray(0).toRequestBody(null)
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
