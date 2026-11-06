package com.wristcord.discord.state

import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.Message

/**
 * Whether a message deserves a notification, following shouldNotify and shouldNotifyBase in
 * Discord's app (see docs/discord-protocol.md). Muted beats everything, mentions included.
 *
 * Two deliberate simplifications: system messages (joins, pins, boosts) never buzz a wrist, and a
 * thread only notifies for a mention rather than tracking per-thread settings.
 */
object NotificationPolicy {
    const val LEVEL_ALL = 0
    const val LEVEL_MENTIONS = 1
    const val LEVEL_NOTHING = 2
    private const val LEVEL_INHERIT = 3

    /** Message flag SUPPRESS_NOTIFICATIONS, set by @silent. */
    private const val FLAG_SILENT = 1L shl 12

    enum class Kind { NONE, MESSAGE, MENTION, DIRECT }

    fun decide(s: Snapshot, m: Message, dmsAndMentionsOnly: Boolean = false, nowMs: Long = System.currentTimeMillis()): Kind {
        val me = s.me ?: return Kind.NONE
        val author = m.author ?: return Kind.NONE
        if (author.id == me.id || author.id in s.blocked) return Kind.NONE
        if (m.flags and FLAG_SILENT != 0L || m.isSystem) return Kind.NONE
        if (s.settings.status == "dnd") return Kind.NONE

        val channel = s.channels[m.channelId]
        if (channel == null && m.guildId == null) return Kind.DIRECT
        if (channel != null) {
            if (channel.id in s.hidden || channel.isMessageRequest || channel.isSpam) return Kind.NONE
            if (channel.isDm) {
                val override = s.guildSettings[0L]?.channelOverrides?.firstOrNull { it.channelId == channel.id }
                return if (s.isChannelMuted(channel, nowMs) || override?.messageNotifications == LEVEL_NOTHING) Kind.NONE else Kind.DIRECT
            }
        }

        val guildId = m.guildId ?: channel?.guildId ?: return Kind.NONE
        val settings = s.guildSettings[guildId]
        if (settings?.mobilePush == false || s.isGuildMuted(guildId, nowMs)) return Kind.NONE
        val isThread = channel != null && ChannelType.isThread(channel.type)
        val source = if (isThread) channel?.parentId?.let { s.channels[it] } ?: channel else channel
        if (source != null && s.isChannelMuted(source, nowMs)) return Kind.NONE

        val level = level(s, guildId, source?.id, source?.parentId)
        if (level == LEVEL_NOTHING) return Kind.NONE
        val myRoles = s.guilds[guildId]?.myRoles.orEmpty()
        val mentioned = m.mentions.any { it.id == me.id } ||
            (m.mentionEveryone && settings?.suppressEveryone != true) ||
            (settings?.suppressRoles != true && m.mentionRoles.any { it in myRoles })
        return when {
            mentioned -> Kind.MENTION
            isThread || dmsAndMentionsOnly || level != LEVEL_ALL -> Kind.NONE
            else -> Kind.MESSAGE
        }
    }

    /** resolvedMessageNotifications: the channel, then its category, then the server. */
    private fun level(s: Snapshot, guildId: Long, channelId: Long?, parentId: Long?): Int {
        val settings = s.guildSettings[guildId]
        fun override(id: Long?) = settings?.channelOverrides?.firstOrNull { it.channelId == id }?.messageNotifications?.takeIf { it != LEVEL_INHERIT }
        return override(channelId)
            ?: override(parentId)
            ?: settings?.messageNotifications?.takeIf { it != LEVEL_INHERIT }
            ?: s.guilds[guildId]?.defaultNotifications
            ?: LEVEL_MENTIONS
    }

    private val USER_MENTION = Regex("<@!?(\\d+)>")
    private val ROLE_MENTION = Regex("<@&(\\d+)>")
    private val CHANNEL_MENTION = Regex("<#(\\d+)>")
    private val CUSTOM_EMOJI = Regex("<a?:(\\w+):\\d+>")
    private val SPOILER = Regex("\\|\\|.+?\\|\\|", RegexOption.DOT_MATCHES_ALL)

    /** What a message says in one notification line. A spoiler stays hidden on a lit-up wrist. */
    fun text(s: Snapshot, m: Message): String {
        val roles = m.guildId?.let { s.guilds[it]?.roles }
        val content = m.content
            .replace(SPOILER, "▒▒▒")
            .replace(USER_MENTION) { r ->
                val id = r.groupValues[1].toLongOrNull()
                val name = m.mentions.firstOrNull { it.id == id }?.displayName ?: id?.let { s.users[it]?.displayName }
                "@${name ?: "someone"}"
            }
            .replace(ROLE_MENTION) { r -> "@${r.groupValues[1].toLongOrNull()?.let { roles?.get(it)?.name } ?: "role"}" }
            .replace(CHANNEL_MENTION) { r -> "#${r.groupValues[1].toLongOrNull()?.let { s.channels[it]?.name } ?: "channel"}" }
            .replace(CUSTOM_EMOJI) { r -> ":${r.groupValues[1]}:" }
            .trim()
        if (content.isNotEmpty()) return content
        val attachment = m.attachments.firstOrNull()
        return when {
            attachment?.durationSecs != null -> "🎤 Voice message"
            attachment?.isSpoiler == true -> "📷 Spoiler"
            attachment?.isImage == true -> "📷 Image"
            attachment != null -> "📎 ${attachment.filename}"
            m.stickers.isNotEmpty() -> "Sticker: ${m.stickers.first().name}"
            m.snapshots.isNotEmpty() -> "Forwarded a message"
            else -> m.embeds.firstOrNull()?.title ?: m.embeds.firstOrNull()?.description ?: "Sent a message"
        }
    }
}
