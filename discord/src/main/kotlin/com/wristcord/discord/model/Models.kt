package com.wristcord.discord.model

import com.wristcord.discord.NullableSnowflakeSerializer
import com.wristcord.discord.PermissionSerializer
import com.wristcord.discord.SnowflakeListSerializer
import com.wristcord.discord.SnowflakeSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Only the fields WristCord reads. [DiscordJson] ignores everything else, which is what keeps a
 * multi-megabyte READY affordable to decode on a watch: unknown keys are skipped by the
 * streaming decoder without ever being built into a tree.
 */
@OptIn(ExperimentalSerializationApi::class)
val DiscordJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

@Serializable
data class User(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val username: String = "",
    @SerialName("global_name") val globalName: String? = null,
    val discriminator: String? = null,
    val avatar: String? = null,
    val bot: Boolean = false,
) {
    /** What Discord shows: display name, falling back to the username. */
    val displayName: String get() = globalName?.takeIf { it.isNotBlank() } ?: username
}

@Serializable
data class Member(
    @SerialName("user_id") @Serializable(NullableSnowflakeSerializer::class) val userId: Long? = null,
    val user: User? = null,
    @Serializable(SnowflakeListSerializer::class) val roles: List<Long> = emptyList(),
    val nick: String? = null,
    val avatar: String? = null,
) {
    val id: Long? get() = userId ?: user?.id
}

@Serializable
data class Role(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val name: String = "",
    val color: Int = 0,
    val position: Int = 0,
    @Serializable(PermissionSerializer::class) val permissions: Long = 0,
)

@Serializable
data class Overwrite(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    /** 0 = role, 1 = member. */
    val type: Int = 0,
    @Serializable(PermissionSerializer::class) val allow: Long = 0,
    @Serializable(PermissionSerializer::class) val deny: Long = 0,
)

@Serializable
data class Channel(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val type: Int = 0,
    @SerialName("guild_id") @Serializable(NullableSnowflakeSerializer::class) val guildId: Long? = null,
    val name: String? = null,
    val position: Int = 0,
    @SerialName("parent_id") @Serializable(NullableSnowflakeSerializer::class) val parentId: Long? = null,
    @SerialName("last_message_id") @Serializable(NullableSnowflakeSerializer::class) val lastMessageId: Long? = null,
    @SerialName("permission_overwrites") val overwrites: List<Overwrite> = emptyList(),
    val topic: String? = null,
    val nsfw: Boolean = false,
    /** DMs / group DMs, deduplicated form (DEDUPE_USER_OBJECTS). */
    @SerialName("recipient_ids") @Serializable(SnowflakeListSerializer::class) val recipientIds: List<Long> = emptyList(),
    /** DMs in their non-deduplicated form (CHANNEL_CREATE carries full users). */
    val recipients: List<User> = emptyList(),
    val icon: String? = null,
    @SerialName("owner_id") @Serializable(NullableSnowflakeSerializer::class) val ownerId: Long? = null,
    @SerialName("user_limit") val userLimit: Int = 0,
    val flags: Long = 0,
    @SerialName("is_message_request") val isMessageRequest: Boolean = false,
    @SerialName("is_spam") val isSpam: Boolean = false,
) {
    val isDm: Boolean get() = type == ChannelType.DM || type == ChannelType.GROUP_DM
}

object ChannelType {
    const val GUILD_TEXT = 0
    const val DM = 1
    const val GUILD_VOICE = 2
    const val GROUP_DM = 3
    const val GUILD_CATEGORY = 4
    const val GUILD_ANNOUNCEMENT = 5
    const val ANNOUNCEMENT_THREAD = 10
    const val PUBLIC_THREAD = 11
    const val PRIVATE_THREAD = 12
    const val GUILD_STAGE_VOICE = 13
    const val GUILD_DIRECTORY = 14
    const val GUILD_FORUM = 15
    const val GUILD_MEDIA = 16

    fun isTextLike(type: Int) = type == GUILD_TEXT || type == GUILD_ANNOUNCEMENT || type == GUILD_VOICE ||
        type == GUILD_STAGE_VOICE || type == DM || type == GROUP_DM ||
        type == PUBLIC_THREAD || type == PRIVATE_THREAD || type == ANNOUNCEMENT_THREAD

    fun isThread(type: Int) = type == PUBLIC_THREAD || type == PRIVATE_THREAD || type == ANNOUNCEMENT_THREAD

    fun isVoice(type: Int) = type == GUILD_VOICE || type == GUILD_STAGE_VOICE
}

@Serializable
data class Emoji(
    @Serializable(NullableSnowflakeSerializer::class) val id: Long? = null,
    val name: String? = null,
    val animated: Boolean = false,
)

/** Guild in READY. With CLIENT_STATE_V2 most scalar fields move under [properties]. */
@Serializable
data class ReadyGuild(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val properties: GuildProperties? = null,
    val name: String? = null,
    val icon: String? = null,
    @SerialName("owner_id") @Serializable(NullableSnowflakeSerializer::class) val ownerId: Long? = null,
    @SerialName("default_message_notifications") val defaultNotifications: Int? = null,
    val channels: List<Channel> = emptyList(),
    val threads: List<Channel> = emptyList(),
    val roles: List<Role> = emptyList(),
    val emojis: List<Emoji> = emptyList(),
    @SerialName("voice_states") val voiceStates: List<VoiceState> = emptyList(),
    val unavailable: Boolean = false,
    @SerialName("member_count") val memberCount: Int = 0,
    /** When this account joined; channels with no read state are unread from here on. */
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
data class GuildProperties(
    val name: String? = null,
    val icon: String? = null,
    @SerialName("owner_id") @Serializable(NullableSnowflakeSerializer::class) val ownerId: Long? = null,
    @SerialName("default_message_notifications") val defaultNotifications: Int? = null,
)

@Serializable
data class VoiceState(
    @SerialName("user_id") @Serializable(SnowflakeSerializer::class) val userId: Long,
    @SerialName("guild_id") @Serializable(NullableSnowflakeSerializer::class) val guildId: Long? = null,
    @SerialName("channel_id") @Serializable(NullableSnowflakeSerializer::class) val channelId: Long? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("self_mute") val selfMute: Boolean = false,
    @SerialName("self_deaf") val selfDeaf: Boolean = false,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    @SerialName("self_video") val selfVideo: Boolean = false,
    @SerialName("self_stream") val selfStream: Boolean = false,
    val suppress: Boolean = false,
    val member: Member? = null,
)

@Serializable
data class ReadStateEntry(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("last_message_id") @Serializable(NullableSnowflakeSerializer::class) val lastMessageId: Long? = null,
    @SerialName("mention_count") val mentionCount: Int = 0,
    /** 0 = channel; NON_CHANNEL_READ_STATES adds others that the watch ignores. */
    @SerialName("read_state_type") val type: Int = 0,
)

@Serializable
data class Versioned<T>(
    val entries: List<T> = emptyList(),
    val partial: Boolean = false,
    val version: Long = 0,
)

@Serializable
data class GuildSettings(
    @SerialName("guild_id") @Serializable(NullableSnowflakeSerializer::class) val guildId: Long? = null,
    val muted: Boolean = false,
    @SerialName("mute_config") val muteConfig: MuteConfig? = null,
    /** 0 all, 1 mentions, 2 nothing, 3 the server's default. */
    @SerialName("message_notifications") val messageNotifications: Int = 3,
    @SerialName("mobile_push") val mobilePush: Boolean = true,
    @SerialName("suppress_everyone") val suppressEveryone: Boolean = false,
    @SerialName("suppress_roles") val suppressRoles: Boolean = false,
    @SerialName("channel_overrides") val channelOverrides: List<ChannelOverride> = emptyList(),
)

@Serializable
data class ChannelOverride(
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    val muted: Boolean = false,
    @SerialName("mute_config") val muteConfig: MuteConfig? = null,
    @SerialName("message_notifications") val messageNotifications: Int = 3,
)

@Serializable
data class MuteConfig(
    @SerialName("end_time") val endTime: String? = null,
)

@Serializable
data class Session(
    @SerialName("session_id") val sessionId: String,
    @SerialName("client_info") val clientInfo: ClientInfo = ClientInfo(),
    val status: String? = null,
    val active: Boolean = false,
)

@Serializable
data class ClientInfo(
    /** "desktop", "web", "mobile", "unknown". */
    val client: String = "unknown",
    /** "android", "ios", "windows", "linux", "osx", ... */
    val os: String = "unknown",
    val version: Int = 0,
)

@Serializable
data class Ready(
    val user: User,
    val users: List<User> = emptyList(),
    val guilds: List<ReadyGuild> = emptyList(),
    @SerialName("private_channels") val privateChannels: List<Channel> = emptyList(),
    @SerialName("merged_members") val mergedMembers: List<List<Member>> = emptyList(),
    @SerialName("read_state") val readState: Versioned<ReadStateEntry> = Versioned(),
    @SerialName("user_guild_settings") val userGuildSettings: Versioned<GuildSettings> = Versioned(),
    @SerialName("user_settings_proto") val userSettingsProto: String? = null,
    val sessions: List<Session> = emptyList(),
    @SerialName("session_id") val sessionId: String,
    @SerialName("resume_gateway_url") val resumeGatewayUrl: String? = null,
    /** AUTH_TOKEN_REFRESH: Discord may hand out a replacement token here. */
    @SerialName("auth_token") val authToken: String? = null,
    val relationships: List<Relationship> = emptyList(),
)

@Serializable
data class Relationship(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    /** 1 friend, 2 blocked, 3 incoming request, 4 outgoing request, 5 ignored. */
    val type: Int = 0,
)

@Serializable
data class ReadySupplemental(
    val guilds: List<SupplementalGuild> = emptyList(),
    @SerialName("merged_members") val mergedMembers: List<List<Member>> = emptyList(),
)

@Serializable
data class SupplementalGuild(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("voice_states") val voiceStates: List<VoiceState> = emptyList(),
)

@Serializable
data class Attachment(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val filename: String = "",
    val url: String = "",
    @SerialName("proxy_url") val proxyUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0,
    val description: String? = null,
    /** Voice messages carry a waveform and duration. */
    @SerialName("duration_secs") val durationSecs: Double? = null,
    val flags: Int = 0,
) {
    val isImage: Boolean
        get() = contentType?.startsWith("image/") == true ||
            filename.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")
    val isSpoiler: Boolean get() = filename.startsWith("SPOILER_") || flags and 8 != 0
}

@Serializable
data class EmbedMedia(
    val url: String? = null,
    @SerialName("proxy_url") val proxyUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class EmbedField(val name: String = "", val value: String = "", val inline: Boolean = false)

@Serializable
data class EmbedText(val name: String? = null, val text: String? = null)

@Serializable
data class Embed(
    val type: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val color: Int? = null,
    val author: EmbedText? = null,
    val footer: EmbedText? = null,
    val provider: EmbedText? = null,
    val image: EmbedMedia? = null,
    val thumbnail: EmbedMedia? = null,
    val fields: List<EmbedField> = emptyList(),
)

@Serializable
data class Reaction(
    val emoji: Emoji,
    val count: Int = 0,
    val me: Boolean = false,
)

@Serializable
data class StickerItem(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val name: String = "",
    /** 1 png, 2 apng, 3 lottie, 4 gif. */
    @SerialName("format_type") val formatType: Int = 1,
)

@Serializable
data class MessageReference(
    @SerialName("message_id") @Serializable(NullableSnowflakeSerializer::class) val messageId: Long? = null,
    @SerialName("channel_id") @Serializable(NullableSnowflakeSerializer::class) val channelId: Long? = null,
    @SerialName("guild_id") @Serializable(NullableSnowflakeSerializer::class) val guildId: Long? = null,
    /** 0 = reply, 1 = forward. */
    val type: Int = 0,
)

@Serializable
data class MessageSnapshot(val message: SnapshotMessage)

@Serializable
data class SnapshotMessage(
    val content: String = "",
    val attachments: List<Attachment> = emptyList(),
    val embeds: List<Embed> = emptyList(),
)

@Serializable
data class Message(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("guild_id") @Serializable(NullableSnowflakeSerializer::class) val guildId: Long? = null,
    val author: User? = null,
    val member: Member? = null,
    val content: String = "",
    val timestamp: String? = null,
    @SerialName("edited_timestamp") val editedTimestamp: String? = null,
    val type: Int = 0,
    val flags: Long = 0,
    @SerialName("mention_everyone") val mentionEveryone: Boolean = false,
    val mentions: List<User> = emptyList(),
    @SerialName("mention_roles") @Serializable(SnowflakeListSerializer::class) val mentionRoles: List<Long> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val embeds: List<Embed> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    @SerialName("sticker_items") val stickers: List<StickerItem> = emptyList(),
    @SerialName("message_reference") val reference: MessageReference? = null,
    @SerialName("referenced_message") val referencedMessage: Message? = null,
    @SerialName("message_snapshots") val snapshots: List<MessageSnapshot> = emptyList(),
    val nonce: JsonElement? = null,
    val pinned: Boolean = false,
) {
    /** System message types the chat renders as a one-line notice rather than a bubble. */
    val isSystem: Boolean get() = type != 0 && type != 19 && type != 20 && type != 23
}

/** MESSAGE_UPDATE can be partial; everything but the ids is optional. */
@Serializable
data class MessageUpdate(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    val content: String? = null,
    @SerialName("edited_timestamp") val editedTimestamp: String? = null,
    val embeds: List<Embed>? = null,
    val attachments: List<Attachment>? = null,
    val author: User? = null,
)

@Serializable
data class MessageDelete(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
)

@Serializable
data class MessageDeleteBulk(
    @Serializable(SnowflakeListSerializer::class) val ids: List<Long> = emptyList(),
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
)

@Serializable
data class ReactionEvent(
    @SerialName("user_id") @Serializable(SnowflakeSerializer::class) val userId: Long,
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("message_id") @Serializable(SnowflakeSerializer::class) val messageId: Long,
    val emoji: Emoji,
)

/** DEBOUNCE_MESSAGE_REACTIONS folds bursts into this. */
@Serializable
data class ReactionAddMany(
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("message_id") @Serializable(SnowflakeSerializer::class) val messageId: Long,
    val reactions: List<ReactionManyEntry> = emptyList(),
)

@Serializable
data class ReactionManyEntry(
    val emoji: Emoji,
    @Serializable(SnowflakeListSerializer::class) val users: List<Long> = emptyList(),
)

@Serializable
data class MessageAck(
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("message_id") @Serializable(NullableSnowflakeSerializer::class) val messageId: Long? = null,
    @SerialName("mention_count") val mentionCount: Int? = null,
    @SerialName("ack_type") val ackType: Int? = null,
)

@Serializable
data class TypingStart(
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("user_id") @Serializable(SnowflakeSerializer::class) val userId: Long,
    val timestamp: Long = 0,
    val member: Member? = null,
)

@Serializable
data class GuildRoleEvent(
    @SerialName("guild_id") @Serializable(SnowflakeSerializer::class) val guildId: Long,
    val role: Role? = null,
    @SerialName("role_id") @Serializable(NullableSnowflakeSerializer::class) val roleId: Long? = null,
)

@Serializable
data class GuildMemberUpdate(
    @SerialName("guild_id") @Serializable(SnowflakeSerializer::class) val guildId: Long,
    val user: User,
    @Serializable(SnowflakeListSerializer::class) val roles: List<Long> = emptyList(),
    val nick: String? = null,
)

@Serializable
data class CallEvent(
    @SerialName("channel_id") @Serializable(SnowflakeSerializer::class) val channelId: Long,
    @SerialName("voice_states") val voiceStates: List<VoiceState> = emptyList(),
    @Serializable(SnowflakeListSerializer::class) val ringing: List<Long> = emptyList(),
)

@Serializable
data class PassiveUpdateV2(
    @SerialName("guild_id") @Serializable(SnowflakeSerializer::class) val guildId: Long,
    @SerialName("updated_voice_states") val updatedVoiceStates: List<VoiceState> = emptyList(),
    @SerialName("removed_voice_states") @Serializable(SnowflakeListSerializer::class)
    val removedVoiceStates: List<Long> = emptyList(),
    @SerialName("updated_channels") val updatedChannels: List<ChannelUnread> = emptyList(),
)

@Serializable
data class ChannelUnreadUpdate(
    @SerialName("guild_id") @Serializable(SnowflakeSerializer::class) val guildId: Long,
    @SerialName("channel_unread_updates") val updates: List<ChannelUnread> = emptyList(),
)

@Serializable
data class ChannelUnread(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    @SerialName("last_message_id") @Serializable(NullableSnowflakeSerializer::class) val lastMessageId: Long? = null,
)

@Serializable
data class SettingsProtoUpdate(
    val settings: SettingsProtoBlob,
    val partial: Boolean = false,
)

@Serializable
data class SettingsProtoBlob(val type: Int = 1, val proto: String = "")

@Serializable
data class GuildDelete(
    @Serializable(SnowflakeSerializer::class) val id: Long,
    val unavailable: Boolean = false,
)
