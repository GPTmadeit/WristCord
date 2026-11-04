package com.wristcord.wear.discord

import android.content.Context
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.state.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The account's voice state as Discord's gateway reports it, flattened and persisted so the
 * tile, complication and watch-face chip can render it from a cold process. This replaces
 * reading mute state off notification button labels: these are Discord's own `self_mute` and
 * `self_deaf` flags.
 */
@Serializable
data class VoiceSnapshot(
    val inCall: Boolean = false,
    val channelId: Long = 0,
    val guildId: Long = 0,
    val channel: String = "",
    val guild: String = "",
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    /** Server-side mute/deafen by a moderator - shown, but not something the user can toggle. */
    val serverMute: Boolean = false,
    val serverDeaf: Boolean = false,
    /** Where the call is running: "phone", "desktop", "web", "console", "watch" or "". */
    val device: String = "",
    val participants: Int = 0,
    val updatedAt: Long = 0,
) {
    val muted: Boolean get() = selfMute || serverMute || selfDeaf || serverDeaf
    val deafened: Boolean get() = selfDeaf || serverDeaf

    /** Older than this and the tile says so instead of presenting it as live. */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - updatedAt < FRESH_MS

    companion object {
        const val FRESH_MS = 3 * 60_000L

        fun from(s: Snapshot): VoiceSnapshot {
            val v = s.myVoice ?: return VoiceSnapshot(updatedAt = System.currentTimeMillis())
            val ch = v.channelId?.let { s.channels[it] }
            val guild = v.guildId?.let { s.guilds[it]?.name }.orEmpty()
            val session = s.sessionFor(v)
            val device = when {
                v.sessionId != null && v.sessionId == s.mySessionId -> "watch"
                session == null -> ""
                session.clientInfo.os == "android" || session.clientInfo.os == "ios" -> "phone"
                session.clientInfo.client == "web" -> "web"
                session.clientInfo.os in setOf("xbox", "playstation") -> "console"
                else -> "desktop"
            }
            return VoiceSnapshot(
                inCall = true,
                channelId = v.channelId ?: 0,
                guildId = v.guildId ?: 0,
                channel = ch?.let { s.channelTitle(it) } ?: if (v.guildId == null) "Call" else "Voice",
                guild = if (ch?.type == ChannelType.DM || ch?.type == ChannelType.GROUP_DM) "Direct call" else guild,
                selfMute = v.selfMute,
                selfDeaf = v.selfDeaf,
                serverMute = v.mute,
                serverDeaf = v.deaf,
                device = device,
                participants = v.channelId?.let { s.voiceIn(it).size } ?: 0,
                updatedAt = System.currentTimeMillis(),
            )
        }

        private const val PREFS = "wristcord_voice"
        private const val K = "snapshot"
        private val json = Json { ignoreUnknownKeys = true }

        fun read(ctx: Context): VoiceSnapshot = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K, null)
                ?.let { json.decodeFromString(serializer(), it) }
        }.getOrNull() ?: VoiceSnapshot()

        fun write(ctx: Context, v: VoiceSnapshot) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K, json.encodeToString(serializer(), v)).apply()
        }

        fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
