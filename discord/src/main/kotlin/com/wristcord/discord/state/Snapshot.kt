package com.wristcord.discord.state

import com.wristcord.discord.Snowflake
import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.GuildSettings
import com.wristcord.discord.model.Role
import com.wristcord.discord.model.Session
import com.wristcord.discord.model.User
import com.wristcord.discord.model.VoiceState
import com.wristcord.discord.proto.Frecency
import com.wristcord.discord.proto.UserSettings
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.time.Instant

data class GuildInfo(
    val id: Long,
    val name: String,
    val icon: String?,
    val ownerId: Long?,
    val roles: PersistentMap<Long, Role>,
    val channelIds: PersistentList<Long>,
    val myRoles: List<Long>,
    val myNick: String?,
    /** Snowflake of the join time; the read position of channels with no read state. */
    val joinedSnowflake: Long,
)

data class ReadState(val lastMessageId: Long = 0, val mentionCount: Int = 0)

/** Key for voice states: a user has at most one per guild, plus one for a DM call (guild 0). */
data class VoiceKey(val guildId: Long, val userId: Long)

/**
 * Everything the watch knows about the account at one instant. Persistent collections share
 * structure between versions, so applying an event costs a few node copies rather than a
 * rebuild - which matters on a watch CPU when a busy server is streaming messages.
 */
data class Snapshot(
    val me: User? = null,
    val users: PersistentMap<Long, User> = persistentMapOf(),
    val guilds: PersistentMap<Long, GuildInfo> = persistentMapOf(),
    val channels: PersistentMap<Long, Channel> = persistentMapOf(),
    val hidden: Set<Long> = emptySet(),
    val readStates: PersistentMap<Long, ReadState> = persistentMapOf(),
    val guildSettings: PersistentMap<Long, GuildSettings> = persistentMapOf(),
    val voice: PersistentMap<VoiceKey, VoiceState> = persistentMapOf(),
    /** Ringing DM calls: channel id -> users being rung. */
    val ringing: PersistentMap<Long, List<Long>> = persistentMapOf(),
    val sessions: List<Session> = emptyList(),
    val mySessionId: String? = null,
    val settings: UserSettings = UserSettings(),
    val frecency: Frecency = Frecency(),
    /** channel id -> (user id -> typing expiry ms). */
    val typing: PersistentMap<Long, PersistentMap<Long, Long>> = persistentMapOf(),
    /** Custom emoji from every server the account is in, by id - for reactions and frecency. */
    val emojis: PersistentMap<Long, Emoji> = persistentMapOf(),
    val ready: Boolean = false,
) {
    val dms: List<Channel>
        get() = channels.values.filter { it.isDm }.sortedByDescending { it.lastMessageId ?: it.id }

    fun guildsInOrder(): List<GuildInfo> {
        val order = settings.guildOrder()
        val seen = HashSet<Long>()
        val out = ArrayList<GuildInfo>(guilds.size)
        for (id in order) guilds[id]?.let { if (seen.add(id)) out += it }
        // Servers joined since the folder list was last saved go on top, as in Discord.
        val rest = guilds.values.filter { it.id !in seen }.sortedByDescending { it.joinedSnowflake }
        return rest + out
    }

    fun channelsOf(guildId: Long): List<Channel> {
        val g = guilds[guildId] ?: return emptyList()
        return g.channelIds.mapNotNull { channels[it] }.filter { it.id !in hidden }
    }

    // ---- read state -------------------------------------------------------------------------

    fun readPosition(channelId: Long): Long {
        readStates[channelId]?.let { return it.lastMessageId }
        val ch = channels[channelId] ?: return 0
        return ch.guildId?.let { guilds[it]?.joinedSnowflake } ?: 0
    }

    fun isUnread(channelId: Long): Boolean {
        val ch = channels[channelId] ?: return false
        val last = ch.lastMessageId ?: return false
        return last > readPosition(channelId)
    }

    fun mentions(channelId: Long): Int = readStates[channelId]?.mentionCount ?: 0

    fun isGuildMuted(guildId: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val gs = guildSettings[guildId] ?: return false
        return gs.muted && muteActive(gs.muteConfig?.endTime, nowMs)
    }

    fun isChannelMuted(channel: Channel, nowMs: Long = System.currentTimeMillis()): Boolean {
        val gs = guildSettings[channel.guildId ?: 0L] ?: return false
        val own = gs.channelOverrides.firstOrNull { it.channelId == channel.id }
        if (own != null && own.muted && muteActive(own.muteConfig?.endTime, nowMs)) return true
        val parent = channel.parentId ?: return false
        val cat = gs.channelOverrides.firstOrNull { it.channelId == parent }
        return cat != null && cat.muted && muteActive(cat.muteConfig?.endTime, nowMs)
    }

    data class Badge(val unread: Boolean, val mentions: Int)

    fun guildBadge(guildId: Long): Badge {
        val g = guilds[guildId] ?: return Badge(false, 0)
        val muted = isGuildMuted(guildId)
        var unread = false
        var mentions = 0
        for (id in g.channelIds) {
            if (id in hidden) continue
            val ch = channels[id] ?: continue
            if (ch.type == ChannelType.GUILD_CATEGORY || ChannelType.isVoice(ch.type)) continue
            mentions += mentions(id)
            if (!unread && !muted && !isChannelMuted(ch) && isUnread(id)) unread = true
        }
        return Badge(unread, mentions)
    }

    fun channelBadge(channelId: Long): Badge {
        val ch = channels[channelId] ?: return Badge(false, 0)
        val mentions = mentions(channelId)
        if (ch.isDm) return Badge(isUnread(channelId), maxOf(mentions, if (isUnread(channelId)) 1 else 0))
        return Badge(isUnread(channelId) && !isChannelMuted(ch), mentions)
    }

    fun dmBadge(): Badge {
        var unread = false
        var mentions = 0
        for (ch in channels.values) {
            if (!ch.isDm) continue
            val b = channelBadge(ch.id)
            unread = unread || b.unread
            mentions += b.mentions
        }
        return Badge(unread, mentions)
    }

    val totalMentions: Int get() = dmBadge().mentions + guilds.keys.sumOf { guildBadge(it).mentions }

    // ---- voice -------------------------------------------------------------------------------

    /** Where this account is in voice right now, on whichever device, or null. */
    val myVoice: VoiceState?
        get() {
            val id = me?.id ?: return null
            return voice.values.firstOrNull { it.userId == id && it.channelId != null }
        }

    fun voiceIn(channelId: Long): List<VoiceState> = voice.values.filter { it.channelId == channelId }

    /** The session holding the voice connection, so the UI can say "on your phone". */
    fun sessionFor(voice: VoiceState): Session? = sessions.firstOrNull { it.sessionId == voice.sessionId }

    // ---- names -------------------------------------------------------------------------------

    fun channelTitle(channel: Channel): String = when (channel.type) {
        ChannelType.DM -> channel.recipientIds.firstOrNull()?.let { users[it]?.displayName } ?: channel.name ?: "Direct message"
        ChannelType.GROUP_DM -> channel.name?.takeIf { it.isNotBlank() }
            ?: channel.recipientIds.mapNotNull { users[it]?.displayName }.joinToString(", ").ifBlank { "Group" }
        else -> channel.name ?: "channel"
    }

    fun displayName(guildId: Long?, user: User): String = user.displayName

    fun typingIn(channelId: Long, nowMs: Long = System.currentTimeMillis()): List<User> =
        typing[channelId].orEmpty().filter { it.value > nowMs && it.key != me?.id }.keys.mapNotNull { users[it] }

    companion object {
        fun muteActive(endTime: String?, nowMs: Long): Boolean {
            if (endTime.isNullOrBlank()) return true
            return runCatching { Instant.parse(endTime).toEpochMilli() > nowMs }.getOrDefault(true)
        }

        fun joinedSnowflake(iso: String?): Long =
            iso?.let { runCatching { Snowflake.fromTimestamp(Instant.parse(it).toEpochMilli()) }.getOrNull() } ?: 0L
    }
}
