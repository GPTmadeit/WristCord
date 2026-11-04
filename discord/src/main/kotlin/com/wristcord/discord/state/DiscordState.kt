package com.wristcord.discord.state

import com.wristcord.discord.model.CallEvent
import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.ChannelUnreadUpdate
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.GuildDelete
import com.wristcord.discord.model.GuildMemberUpdate
import com.wristcord.discord.model.GuildRoleEvent
import com.wristcord.discord.model.GuildSettings
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.MessageAck
import com.wristcord.discord.model.PassiveUpdateV2
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadyGuild
import com.wristcord.discord.model.ReadySupplemental
import com.wristcord.discord.model.Session
import com.wristcord.discord.model.SettingsProtoUpdate
import com.wristcord.discord.model.TypingStart
import com.wristcord.discord.model.User
import com.wristcord.discord.model.VoiceState
import com.wristcord.discord.proto.Frecency
import com.wristcord.discord.proto.UserSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** Something the Android layer may want to react to: notify, buzz, refresh a tile. */
sealed interface StoreEvent {
    data class NewMessage(val message: Message, val mentionsMe: Boolean, val isDm: Boolean) : StoreEvent
    data class MessageRead(val channelId: Long) : StoreEvent
    data object VoiceChanged : StoreEvent
    data object SettingsChanged : StoreEvent
    /** Where to connect for voice; arrives after this session asks to join (op 4). */
    data class VoiceServer(val token: String, val guildId: Long?, val channelId: Long?, val endpoint: String?) : StoreEvent
    /** This account's voice state changed, from whichever session holds it. */
    data class MyVoiceState(val state: VoiceState) : StoreEvent
}

/**
 * The account's live state, built from READY and kept current by dispatch events. All
 * mutation goes through [update] under one lock; readers get immutable [Snapshot]s.
 */
class DiscordState {

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val _events = MutableSharedFlow<StoreEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<StoreEvent> = _events

    /** Message caches for open channels; DiscordState feeds them live events. */
    val messages = MessageStore()

    private val lock = Any()

    fun update(block: (Snapshot) -> Snapshot) {
        synchronized(lock) { _snapshot.value = block(_snapshot.value) }
    }

    val value: Snapshot get() = _snapshot.value

    fun clear() {
        synchronized(lock) { _snapshot.value = Snapshot() }
        messages.clear()
    }

    // ---- READY -------------------------------------------------------------------------------

    fun onReady(ready: Ready) {
        val me = ready.user
        val users = HashMap<Long, User>(ready.users.size + 1)
        for (u in ready.users) users[u.id] = u
        users[me.id] = me

        val channels = HashMap<Long, Channel>()
        val hidden = HashSet<Long>()
        val guilds = HashMap<Long, GuildInfo>()
        val voice = HashMap<VoiceKey, VoiceState>()
        val emojis = HashMap<Long, com.wristcord.discord.model.Emoji>()

        ready.guilds.forEachIndexed { i, g ->
            if (g.unavailable) return@forEachIndexed
            val mine = ready.mergedMembers.getOrNull(i)?.firstOrNull { it.id == me.id }
            val info = guildInfo(g, mine?.roles.orEmpty(), mine?.nick)
            guilds[g.id] = info
            for (c in g.channels + g.threads) {
                val ch = c.copy(guildId = g.id)
                channels[ch.id] = ch
                if (!Permissions.canView(ch, g.id, info.ownerId, me.id, info.myRoles, info.roles)) hidden += ch.id
            }
            for (vs in g.voiceStates) voice[VoiceKey(g.id, vs.userId)] = vs.copy(guildId = g.id)
            for (e in g.emojis) e.id?.let { emojis[it] = e }
        }
        for (c in ready.privateChannels) {
            channels[c.id] = c
            for (u in c.recipients) users[u.id] = u
        }

        val reads = HashMap<Long, ReadState>()
        for (e in ready.readState.entries) if (e.type == 0) reads[e.id] = ReadState(e.lastMessageId ?: 0, e.mentionCount)

        val gs = HashMap<Long, GuildSettings>()
        for (e in ready.userGuildSettings.entries) gs[e.guildId ?: 0L] = e

        val settings = runCatching { UserSettings.parseBase64(ready.userSettingsProto) }.getOrDefault(UserSettings())

        synchronized(lock) {
            val prev = _snapshot.value
            _snapshot.value = Snapshot(
                me = me,
                users = users.toPersistentMap(),
                guilds = guilds.toPersistentMap(),
                channels = channels.toPersistentMap(),
                hidden = hidden,
                readStates = reads.toPersistentMap(),
                guildSettings = gs.toPersistentMap(),
                voice = voice.toPersistentMap(),
                sessions = ready.sessions,
                mySessionId = ready.sessionId,
                settings = settings,
                frecency = prev.frecency,
                emojis = emojis.toPersistentMap(),
                ready = true,
            )
        }
        _events.tryEmit(StoreEvent.SettingsChanged)
        _events.tryEmit(StoreEvent.VoiceChanged)
    }

    fun onReadySupplemental(s: ReadySupplemental) {
        update { snap ->
            var voice = snap.voice
            for (g in s.guilds) for (vs in g.voiceStates) voice = voice.put(VoiceKey(g.id, vs.userId), vs.copy(guildId = g.id))
            snap.copy(voice = voice)
        }
        _events.tryEmit(StoreEvent.VoiceChanged)
    }

    fun setFrecency(f: Frecency) = update { it.copy(frecency = f) }

    private fun guildInfo(g: ReadyGuild, myRoles: List<Long>, nick: String?): GuildInfo {
        val roles = g.roles.associateBy { it.id }.toPersistentMap()
        val sorted = (g.channels).sortedWith(compareBy({ it.position }, { it.id })).map { it.id }
        return GuildInfo(
            id = g.id,
            name = g.properties?.name ?: g.name ?: "Server",
            icon = g.properties?.icon ?: g.icon,
            ownerId = g.properties?.ownerId ?: g.ownerId,
            roles = roles,
            channelIds = sorted.toPersistentList(),
            myRoles = myRoles,
            myNick = nick,
            joinedSnowflake = Snapshot.joinedSnowflake(g.joinedAt),
        )
    }

    // ---- dispatch ----------------------------------------------------------------------------

    fun onDispatch(type: String, d: JsonElement) {
        when (type) {
            "MESSAGE_CREATE" -> onMessageCreate(decode(Message.serializer(), d))
            "MESSAGE_UPDATE", "MESSAGE_DELETE", "MESSAGE_DELETE_BULK",
            "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE", "MESSAGE_REACTION_ADD_MANY",
            "MESSAGE_REACTION_REMOVE_ALL", "MESSAGE_REACTION_REMOVE_EMOJI" -> messages.onDispatch(type, d, value.me?.id)
            "MESSAGE_ACK" -> onAck(decode(MessageAck.serializer(), d))
            "CHANNEL_CREATE", "CHANNEL_UPDATE" -> onChannel(decode(Channel.serializer(), d))
            "CHANNEL_DELETE" -> {
                val id = decode(Channel.serializer(), d).id
                update { s -> s.copy(channels = s.channels.remove(id)) }
            }
            "THREAD_CREATE", "THREAD_UPDATE" -> onChannel(decode(Channel.serializer(), d))
            "GUILD_CREATE" -> onGuildCreate(decode(ReadyGuild.serializer(), d))
            "GUILD_UPDATE" -> onGuildUpdate(d.jsonObject)
            "GUILD_DELETE" -> {
                val g = decode(GuildDelete.serializer(), d)
                if (!g.unavailable) update { s -> s.copy(guilds = s.guilds.remove(g.id)) }
            }
            "GUILD_ROLE_CREATE", "GUILD_ROLE_UPDATE" -> onRole(decode(GuildRoleEvent.serializer(), d), delete = false)
            "GUILD_ROLE_DELETE" -> onRole(decode(GuildRoleEvent.serializer(), d), delete = true)
            "GUILD_MEMBER_UPDATE" -> onMemberUpdate(decode(GuildMemberUpdate.serializer(), d))
            "VOICE_STATE_UPDATE" -> onVoiceState(decode(VoiceState.serializer(), d))
            "VOICE_SERVER_UPDATE" -> {
                val o = d.jsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val token = str("token") ?: return
                _events.tryEmit(StoreEvent.VoiceServer(token, str("guild_id")?.toLongOrNull(), str("channel_id")?.toLongOrNull(), str("endpoint")))
            }
            "CALL_CREATE", "CALL_UPDATE" -> onCall(decode(CallEvent.serializer(), d))
            "CALL_DELETE" -> onCallDelete(decode(CallEvent.serializer(), d).channelId)
            "PASSIVE_UPDATE_V2" -> onPassive(decode(PassiveUpdateV2.serializer(), d))
            "CHANNEL_UNREAD_UPDATE" -> onUnreadUpdate(decode(ChannelUnreadUpdate.serializer(), d))
            "SESSIONS_REPLACE" -> {
                val sessions = decode(ListSerializer(Session.serializer()), d)
                update { it.copy(sessions = sessions) }
                _events.tryEmit(StoreEvent.VoiceChanged)
            }
            "USER_SETTINGS_PROTO_UPDATE" -> onSettingsProto(decode(SettingsProtoUpdate.serializer(), d))
            "USER_GUILD_SETTINGS_UPDATE" -> {
                val gs = decode(GuildSettings.serializer(), d)
                update { s -> s.copy(guildSettings = s.guildSettings.put(gs.guildId ?: 0L, gs)) }
            }
            "USER_UPDATE" -> {
                val u = decode(User.serializer(), d)
                update { s -> if (s.me?.id == u.id) s.copy(me = u, users = s.users.put(u.id, u)) else s }
            }
            "TYPING_START" -> onTyping(decode(TypingStart.serializer(), d))
            "GUILD_EMOJIS_UPDATE" -> {
                val list = d.jsonObject["emojis"]?.let { decode(ListSerializer(com.wristcord.discord.model.Emoji.serializer()), it) }.orEmpty()
                update { s -> s.copy(emojis = s.emojis.putAll(list.filter { it.id != null }.associateBy { it.id!! })) }
            }
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, d: JsonElement): T = DiscordJson.decodeFromJsonElement(serializer, d)

    private fun onMessageCreate(m: Message) {
        val me = value.me ?: return
        var mentionsMe = false
        var isDm = false
        update { s ->
            val ch = s.channels[m.channelId]
            isDm = ch?.isDm == true || (ch == null && m.guildId == null)
            val channels = if (ch != null) s.channels.put(m.channelId, ch.copy(lastMessageId = m.id)) else s.channels
            val users = m.author?.let { s.users.put(it.id, it) } ?: s.users
            var reads = s.readStates
            if (m.author?.id == me.id) {
                reads = reads.put(m.channelId, ReadState(m.id, 0))
            } else {
                val myRoles = m.guildId?.let { s.guilds[it]?.myRoles }.orEmpty()
                mentionsMe = isDm || m.mentions.any { it.id == me.id } || m.mentionEveryone ||
                    m.mentionRoles.any { it in myRoles }
                if (mentionsMe) {
                    val rs = reads[m.channelId] ?: ReadState(s.readPosition(m.channelId), 0)
                    reads = reads.put(m.channelId, rs.copy(mentionCount = rs.mentionCount + 1))
                }
            }
            var typing = s.typing
            m.author?.let { a -> typing[m.channelId]?.let { typing = typing.put(m.channelId, it.remove(a.id)) } }
            s.copy(channels = channels, users = users, readStates = reads, typing = typing)
        }
        messages.onCreate(m, me.id)
        if (m.author?.id != me.id) _events.tryEmit(StoreEvent.NewMessage(m, mentionsMe, isDm))
    }

    private fun onAck(a: MessageAck) {
        val id = a.messageId ?: return
        update { s -> s.copy(readStates = s.readStates.put(a.channelId, ReadState(id, a.mentionCount ?: 0))) }
        _events.tryEmit(StoreEvent.MessageRead(a.channelId))
    }

    /** Called when this watch acks; the server echoes MESSAGE_ACK only to other sessions. */
    fun markReadLocally(channelId: Long, messageId: Long) {
        update { s -> s.copy(readStates = s.readStates.put(channelId, ReadState(messageId, 0))) }
        _events.tryEmit(StoreEvent.MessageRead(channelId))
    }

    private fun onChannel(c: Channel) {
        update { s ->
            val prev = s.channels[c.id]
            val merged = if (prev != null && c.lastMessageId == null) c.copy(lastMessageId = prev.lastMessageId) else c
            var guilds = s.guilds
            var hidden = s.hidden
            var users = s.users
            for (u in c.recipients) users = users.put(u.id, u)
            val gid = c.guildId
            val ch = if (c.isDm && c.recipientIds.isEmpty() && c.recipients.isNotEmpty()) merged.copy(recipientIds = c.recipients.map { it.id }) else merged
            if (gid != null) {
                val g = guilds[gid]
                if (g != null) {
                    if (ch.id !in g.channelIds && ch.type !in THREAD_TYPES) {
                        guilds = guilds.put(gid, g.copy(channelIds = (g.channelIds + ch.id).sortedBy { s.channels[it]?.position ?: ch.position }.toPersistentList()))
                    }
                    val me = s.me
                    if (me != null) {
                        hidden = if (Permissions.canView(ch, gid, g.ownerId, me.id, g.myRoles, g.roles)) hidden - ch.id else hidden + ch.id
                    }
                }
            }
            s.copy(channels = s.channels.put(ch.id, ch), guilds = guilds, hidden = hidden, users = users)
        }
    }

    private fun onGuildCreate(g: ReadyGuild) {
        val me = value.me ?: return
        update { s ->
            val info = guildInfo(g, s.guilds[g.id]?.myRoles.orEmpty(), s.guilds[g.id]?.myNick)
            var channels = s.channels
            var hidden = s.hidden
            for (c in g.channels + g.threads) {
                val ch = c.copy(guildId = g.id)
                channels = channels.put(ch.id, ch)
                hidden = if (Permissions.canView(ch, g.id, info.ownerId, me.id, info.myRoles, info.roles)) hidden - ch.id else hidden + ch.id
            }
            var voice = s.voice
            for (vs in g.voiceStates) voice = voice.put(VoiceKey(g.id, vs.userId), vs.copy(guildId = g.id))
            s.copy(guilds = s.guilds.put(g.id, info), channels = channels, hidden = hidden, voice = voice)
        }
    }

    private fun onGuildUpdate(o: JsonObject) {
        val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        update { s ->
            val g = s.guilds[id] ?: return@update s
            val name = (o["name"] as? JsonPrimitive)?.content ?: g.name
            val icon = (o["icon"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
            s.copy(guilds = s.guilds.put(id, g.copy(name = name, icon = icon)))
        }
    }

    private fun onRole(e: GuildRoleEvent, delete: Boolean) {
        update { s ->
            val g = s.guilds[e.guildId] ?: return@update s
            val roles = if (delete) g.roles.remove(e.roleId ?: e.role?.id ?: 0L) else e.role?.let { g.roles.put(it.id, it) } ?: g.roles
            recomputeHidden(s.copy(guilds = s.guilds.put(g.id, g.copy(roles = roles))), g.id)
        }
    }

    private fun onMemberUpdate(e: GuildMemberUpdate) {
        update { s ->
            if (e.user.id != s.me?.id) return@update s
            val g = s.guilds[e.guildId] ?: return@update s
            recomputeHidden(s.copy(guilds = s.guilds.put(g.id, g.copy(myRoles = e.roles, myNick = e.nick))), g.id)
        }
    }

    private fun recomputeHidden(s: Snapshot, guildId: Long): Snapshot {
        val g = s.guilds[guildId] ?: return s
        val me = s.me ?: return s
        val hidden = HashSet(s.hidden)
        for (id in g.channelIds) {
            val ch = s.channels[id] ?: continue
            if (Permissions.canView(ch, guildId, g.ownerId, me.id, g.myRoles, g.roles)) hidden -= id else hidden += id
        }
        return s.copy(hidden = hidden)
    }

    private fun onVoiceState(vs: VoiceState) {
        update { s ->
            val key = VoiceKey(vs.guildId ?: 0L, vs.userId)
            val voice = if (vs.channelId == null) s.voice.remove(key) else s.voice.put(key, vs)
            val users = vs.member?.user?.let { s.users.put(it.id, it) } ?: s.users
            s.copy(voice = voice, users = users)
        }
        if (vs.userId == value.me?.id) _events.tryEmit(StoreEvent.MyVoiceState(vs))
        _events.tryEmit(StoreEvent.VoiceChanged)
    }

    private fun onCall(c: CallEvent) {
        update { s ->
            var voice = s.voice
            for (vs in c.voiceStates) voice = voice.put(VoiceKey(0L, vs.userId), vs.copy(guildId = null, channelId = vs.channelId ?: c.channelId))
            s.copy(voice = voice, ringing = s.ringing.put(c.channelId, c.ringing))
        }
        _events.tryEmit(StoreEvent.VoiceChanged)
    }

    private fun onCallDelete(channelId: Long) {
        update { s ->
            val voice = s.voice.filterValues { it.channelId != channelId || it.guildId != null }
            s.copy(voice = voice.toPersistentMap(), ringing = s.ringing.remove(channelId))
        }
        _events.tryEmit(StoreEvent.VoiceChanged)
    }

    private fun onPassive(p: PassiveUpdateV2) {
        update { s ->
            var channels = s.channels
            for (u in p.updatedChannels) channels[u.id]?.let { channels = channels.put(u.id, it.copy(lastMessageId = u.lastMessageId ?: it.lastMessageId)) }
            var voice = s.voice
            for (vs in p.updatedVoiceStates) voice = voice.put(VoiceKey(p.guildId, vs.userId), vs.copy(guildId = p.guildId))
            for (uid in p.removedVoiceStates) voice = voice.remove(VoiceKey(p.guildId, uid))
            s.copy(channels = channels, voice = voice)
        }
        if (p.updatedVoiceStates.isNotEmpty() || p.removedVoiceStates.isNotEmpty()) _events.tryEmit(StoreEvent.VoiceChanged)
    }

    private fun onUnreadUpdate(u: ChannelUnreadUpdate) {
        update { s ->
            var channels = s.channels
            for (c in u.updates) channels[c.id]?.let { channels = channels.put(c.id, it.copy(lastMessageId = c.lastMessageId ?: it.lastMessageId)) }
            s.copy(channels = channels)
        }
    }

    private fun onSettingsProto(u: SettingsProtoUpdate) {
        if (u.settings.type != 1) return
        val bytes = runCatching { Base64.getDecoder().decode(u.settings.proto) }.getOrNull() ?: return
        update { s -> s.copy(settings = if (u.partial) UserSettings.merge(s.settings, bytes) else UserSettings.parse(bytes)) }
        _events.tryEmit(StoreEvent.SettingsChanged)
    }

    fun applySettingsBase64(b64: String) {
        if (b64.isBlank()) return
        update { s -> s.copy(settings = UserSettings.parseBase64(b64)) }
        _events.tryEmit(StoreEvent.SettingsChanged)
    }

    private fun onTyping(t: TypingStart) {
        update { s ->
            val users = t.member?.user?.let { s.users.put(it.id, it) } ?: s.users
            val forChannel = (s.typing[t.channelId] ?: persistentMapOf()).put(t.userId, System.currentTimeMillis() + TYPING_MS)
            s.copy(typing = s.typing.put(t.channelId, forChannel), users = users)
        }
    }

    private companion object {
        const val TYPING_MS = 10_000L
        val THREAD_TYPES = setOf(ChannelType.PUBLIC_THREAD, ChannelType.PRIVATE_THREAD, ChannelType.ANNOUNCEMENT_THREAD)
    }
}

@Suppress("unused")
private val EMPTY_IDS = persistentListOf<Long>()
