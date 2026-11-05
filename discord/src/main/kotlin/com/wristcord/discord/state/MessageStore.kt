package com.wristcord.discord.state

import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.MessageDelete
import com.wristcord.discord.model.MessageDeleteBulk
import com.wristcord.discord.model.MessageUpdate
import com.wristcord.discord.model.Reaction
import com.wristcord.discord.model.ReactionAddMany
import com.wristcord.discord.model.ReactionEvent
import com.wristcord.discord.model.User
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** A message the watch has sent but Discord has not echoed back yet. */
data class PendingMessage(
    val nonce: String,
    val channelId: Long,
    val content: String,
    val author: User,
    val replyTo: Long?,
    val failed: String? = null,
    /** The picture on its way, kept on disk until Discord has it. */
    val image: File? = null,
)

data class ChannelMessages(
    /** Oldest first. */
    val messages: PersistentList<Message> = persistentListOf(),
    val pending: List<PendingMessage> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * Message history for the few channels that are open or were recently. Older channels are
 * evicted - a watch has no business holding every conversation in memory.
 */
class MessageStore {

    private val flows = object : LinkedHashMap<Long, MutableStateFlow<ChannelMessages>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableStateFlow<ChannelMessages>>?) = size > MAX_CHANNELS
    }

    @Synchronized
    fun flow(channelId: Long): StateFlow<ChannelMessages> = slot(channelId)

    @Synchronized
    fun peek(channelId: Long): ChannelMessages? = flows[channelId]?.value

    @Synchronized
    fun clear() = flows.clear()

    private fun slot(channelId: Long) = flows.getOrPut(channelId) { MutableStateFlow(ChannelMessages()) }

    @Synchronized
    fun mutate(channelId: Long, block: (ChannelMessages) -> ChannelMessages) {
        val f = slot(channelId)
        f.value = block(f.value)
    }

    private fun mutateIfOpen(channelId: Long, block: (ChannelMessages) -> ChannelMessages) = synchronized(this) {
        flows[channelId]?.let { it.value = block(it.value) }
    }

    /** REST pages arrive newest first. */
    fun loaded(channelId: Long, newestFirst: List<Message>, older: Boolean, pageSize: Int) = mutate(channelId) { cm ->
        val page = newestFirst.asReversed()
        val merged = if (older) (page + cm.messages) else mergeLatest(cm.messages, page)
        cm.copy(
            messages = merged.distinctBy { it.id }.toPersistentList(),
            loaded = true,
            loading = false,
            hasMore = if (older || !cm.loaded) newestFirst.size >= pageSize else cm.hasMore,
            error = null,
        )
    }

    private fun mergeLatest(existing: List<Message>, page: List<Message>): List<Message> {
        if (existing.isEmpty()) return page
        val byId = LinkedHashMap<Long, Message>()
        for (m in existing) byId[m.id] = m
        for (m in page) byId[m.id] = m
        return byId.values.sortedBy { it.id }
    }

    fun onCreate(m: Message, myId: Long) = mutateIfOpen(m.channelId) { cm ->
        val nonce = (m.nonce as? JsonPrimitive)?.content
        val pending = if (nonce != null) cm.pending.filterNot { it.nonce == nonce } else cm.pending
        if (!cm.loaded || cm.messages.any { it.id == m.id }) cm.copy(pending = pending)
        else cm.copy(messages = cm.messages.add(m), pending = pending)
    }

    fun onDispatch(type: String, d: JsonElement, myId: Long?) {
        when (type) {
            "MESSAGE_UPDATE" -> {
                val u = DiscordJson.decodeFromJsonElement(MessageUpdate.serializer(), d)
                mutateIfOpen(u.channelId) { cm ->
                    cm.copy(messages = cm.messages.map { m ->
                        if (m.id != u.id) m else m.copy(
                            content = u.content ?: m.content,
                            editedTimestamp = u.editedTimestamp ?: m.editedTimestamp,
                            embeds = u.embeds ?: m.embeds,
                            attachments = u.attachments ?: m.attachments,
                        )
                    }.toPersistentList())
                }
            }
            "MESSAGE_DELETE" -> {
                val del = DiscordJson.decodeFromJsonElement(MessageDelete.serializer(), d)
                mutateIfOpen(del.channelId) { cm -> cm.copy(messages = cm.messages.filterNot { it.id == del.id }.toPersistentList()) }
            }
            "MESSAGE_DELETE_BULK" -> {
                val del = DiscordJson.decodeFromJsonElement(MessageDeleteBulk.serializer(), d)
                val ids = del.ids.toSet()
                mutateIfOpen(del.channelId) { cm -> cm.copy(messages = cm.messages.filterNot { it.id in ids }.toPersistentList()) }
            }
            "MESSAGE_REACTION_ADD" -> {
                val e = DiscordJson.decodeFromJsonElement(ReactionEvent.serializer(), d)
                react(e.channelId, e.messageId, e.emoji, +1, e.userId == myId)
            }
            "MESSAGE_REACTION_REMOVE" -> {
                val e = DiscordJson.decodeFromJsonElement(ReactionEvent.serializer(), d)
                react(e.channelId, e.messageId, e.emoji, -1, e.userId == myId)
            }
            "MESSAGE_REACTION_ADD_MANY" -> {
                val e = DiscordJson.decodeFromJsonElement(ReactionAddMany.serializer(), d)
                for (r in e.reactions) react(e.channelId, e.messageId, r.emoji, r.users.size, myId != null && myId in r.users)
            }
            "MESSAGE_REACTION_REMOVE_ALL" -> {
                val o = d.jsonObject
                val cid = (o["channel_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
                val mid = (o["message_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
                mutateIfOpen(cid) { cm -> cm.copy(messages = cm.messages.map { if (it.id == mid) it.copy(reactions = emptyList()) else it }.toPersistentList()) }
            }
            "MESSAGE_REACTION_REMOVE_EMOJI" -> {
                val e = DiscordJson.decodeFromJsonElement(ReactionEvent.serializer(), d)
                mutateIfOpen(e.channelId) { cm ->
                    cm.copy(messages = cm.messages.map { m ->
                        if (m.id != e.messageId) m else m.copy(reactions = m.reactions.filterNot { sameEmoji(it.emoji, e.emoji) })
                    }.toPersistentList())
                }
            }
        }
    }

    /** Applies a reaction delta; also used optimistically when the watch reacts. */
    fun react(channelId: Long, messageId: Long, emoji: Emoji, delta: Int, byMe: Boolean) = mutateIfOpen(channelId) { cm ->
        cm.copy(messages = cm.messages.map { m ->
            if (m.id != messageId) return@map m
            val existing = m.reactions.firstOrNull { sameEmoji(it.emoji, emoji) }
            val updated = when {
                existing == null && delta > 0 -> m.reactions + Reaction(emoji, delta, byMe)
                existing == null -> m.reactions
                else -> m.reactions.mapNotNull { r ->
                    if (!sameEmoji(r.emoji, emoji)) r
                    else {
                        val count = r.count + delta
                        if (count <= 0) null else r.copy(count = count, me = if (byMe) delta > 0 else r.me)
                    }
                }
            }
            m.copy(reactions = updated)
        }.toPersistentList())
    }

    fun addPending(p: PendingMessage) = mutate(p.channelId) { it.copy(pending = it.pending + p) }

    fun failPending(channelId: Long, nonce: String, reason: String) = mutateIfOpen(channelId) { cm ->
        cm.copy(pending = cm.pending.map { if (it.nonce == nonce) it.copy(failed = reason) else it })
    }

    fun dropPending(channelId: Long, nonce: String) = mutateIfOpen(channelId) { cm -> cm.copy(pending = cm.pending.filterNot { it.nonce == nonce }) }

    companion object {
        const val MAX_CHANNELS = 6

        fun sameEmoji(a: Emoji, b: Emoji): Boolean =
            if (a.id != null || b.id != null) a.id == b.id else a.name == b.name
    }
}
