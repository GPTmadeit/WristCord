package com.wristcord.discord

import com.wristcord.discord.model.Attachment
import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.ChannelOverride
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.GuildSettings
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.MuteConfig
import com.wristcord.discord.model.User
import com.wristcord.discord.proto.UserSettings
import com.wristcord.discord.state.DiscordState
import com.wristcord.discord.state.GuildInfo
import com.wristcord.discord.state.NotificationPolicy
import com.wristcord.discord.state.NotificationPolicy.Kind
import com.wristcord.discord.state.ReadState
import com.wristcord.discord.state.Snapshot
import com.wristcord.discord.state.StoreEvent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPolicyTest {
    private val me = User(id = 10, username = "neo")
    private val them = User(id = 20, username = "trinity")
    private val guild = 1000L
    private val text = 2L
    private val category = 3L
    private val dm = 5L
    private val thread = 6L
    private val hidden = 7L

    private fun snapshot(settings: GuildSettings? = null, status: String = "online", default: Int = NotificationPolicy.LEVEL_ALL, dmRequest: Boolean = false) = Snapshot(
        me = me,
        users = persistentMapOf(10L to me, 20L to them),
        guilds = persistentMapOf(guild to GuildInfo(guild, "Zion", null, 30L, persistentMapOf(), persistentListOf(), listOf(99L), null, 0L, default)),
        channels = persistentMapOf(
            text to Channel(id = text, name = "general", guildId = guild, parentId = category),
            dm to Channel(id = dm, type = ChannelType.DM, recipientIds = listOf(20L), isMessageRequest = dmRequest),
            thread to Channel(id = thread, type = ChannelType.PUBLIC_THREAD, guildId = guild, parentId = text),
            hidden to Channel(id = hidden, guildId = guild),
        ),
        hidden = setOf(hidden),
        guildSettings = if (settings == null) persistentMapOf() else persistentMapOf((settings.guildId ?: 0L) to settings),
        settings = UserSettings(status = status),
    )

    private fun msg(channel: Long = text, content: String = "hi", mention: Boolean = false, everyone: Boolean = false, role: Boolean = false, flags: Long = 0, author: User = them, type: Int = 0) = Message(
        id = 500, channelId = channel, guildId = if (channel == dm) null else guild, author = author, content = content,
        mentions = if (mention) listOf(me) else emptyList(), mentionEveryone = everyone, mentionRoles = if (role) listOf(99L) else emptyList(), flags = flags, type = type,
    )

    @Test
    fun `a DM always notifies unless muted`() {
        assertEquals(Kind.DIRECT, NotificationPolicy.decide(snapshot(), msg(dm)))
        val muted = GuildSettings(guildId = null, channelOverrides = listOf(ChannelOverride(dm, muted = true)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(muted), msg(dm)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(dmRequest = true), msg(dm)))
    }

    @Test
    fun `what never notifies`() {
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(), msg(author = me)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(status = "dnd"), msg(dm)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot().copy(blocked = setOf(20L)), msg(dm)))
        assertEquals("@silent", Kind.NONE, NotificationPolicy.decide(snapshot(), msg(dm, flags = 1L shl 12)))
        assertEquals("a channel it cannot see", Kind.NONE, NotificationPolicy.decide(snapshot(), msg(hidden)))
        assertEquals("system message", Kind.NONE, NotificationPolicy.decide(snapshot(), msg(type = 7)))
    }

    @Test
    fun `levels resolve channel then category then server then server default`() {
        fun level(s: GuildSettings?, default: Int = 0) = NotificationPolicy.decide(snapshot(s, default = default), msg())
        assertEquals(Kind.MESSAGE, level(null))
        assertEquals(Kind.NONE, level(null, default = 1))
        assertEquals(Kind.NONE, level(GuildSettings(guildId = guild, messageNotifications = 1)))
        assertEquals(Kind.MESSAGE, level(GuildSettings(guildId = guild, messageNotifications = 3)))
        assertEquals(Kind.MESSAGE, level(GuildSettings(guildId = guild, messageNotifications = 1, channelOverrides = listOf(ChannelOverride(category, messageNotifications = 0)))))
        assertEquals(Kind.NONE, level(GuildSettings(guildId = guild, messageNotifications = 0, channelOverrides = listOf(ChannelOverride(category, messageNotifications = 0), ChannelOverride(text, messageNotifications = 2)))))
    }

    @Test
    fun `mentions notify at mentions level but not at nothing`() {
        val mentions = GuildSettings(guildId = guild, messageNotifications = 1)
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(mentions), msg(mention = true)))
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(mentions), msg(everyone = true)))
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(mentions), msg(role = true)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(GuildSettings(guildId = guild, messageNotifications = 2)), msg(mention = true)))
    }

    @Test
    fun `suppressed everyone and roles still leave direct mentions`() {
        val s = GuildSettings(guildId = guild, messageNotifications = 1, suppressEveryone = true, suppressRoles = true)
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(s), msg(everyone = true)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(s), msg(role = true)))
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(s), msg(mention = true)))
    }

    @Test
    fun `muted beats a mention at every level and a mute can expire`() {
        val now = 1_800_000_000_000L
        fun decide(s: GuildSettings) = NotificationPolicy.decide(snapshot(s), msg(mention = true), nowMs = now)
        assertEquals(Kind.NONE, decide(GuildSettings(guildId = guild, muted = true)))
        assertEquals(Kind.NONE, decide(GuildSettings(guildId = guild, channelOverrides = listOf(ChannelOverride(text, muted = true)))))
        assertEquals(Kind.NONE, decide(GuildSettings(guildId = guild, channelOverrides = listOf(ChannelOverride(category, muted = true)))))
        assertEquals(Kind.MENTION, decide(GuildSettings(guildId = guild, muted = true, muteConfig = MuteConfig("2020-01-01T00:00:00Z"))))
        assertEquals("mobile push off", Kind.NONE, decide(GuildSettings(guildId = guild, mobilePush = false)))
    }

    @Test
    fun `threads notify for mentions only and follow the parent's mute`() {
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(), msg(thread)))
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(), msg(thread, mention = true)))
        val muted = GuildSettings(guildId = guild, channelOverrides = listOf(ChannelOverride(text, muted = true)))
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(muted), msg(thread, mention = true)))
    }

    @Test
    fun `DMs and mentions mode drops ordinary server messages only`() {
        assertEquals(Kind.NONE, NotificationPolicy.decide(snapshot(), msg(), dmsAndMentionsOnly = true))
        assertEquals(Kind.MENTION, NotificationPolicy.decide(snapshot(), msg(mention = true), dmsAndMentionsOnly = true))
        assertEquals(Kind.DIRECT, NotificationPolicy.decide(snapshot(), msg(dm), dmsAndMentionsOnly = true))
    }

    @Test
    fun `the line reads like a person wrote it and keeps spoilers hidden`() {
        val s = snapshot()
        assertEquals("hey @neo see #general :wave:", NotificationPolicy.text(s, msg(content = "hey <@10> see <#2> <a:wave:123>", mention = true)))
        assertEquals("the ending: ▒▒▒", NotificationPolicy.text(s, msg(content = "the ending: ||he dies||")))
        val photo = Attachment(id = 1, filename = "a.png", contentType = "image/png", url = "u", proxyUrl = "p")
        assertEquals("📷 Image", NotificationPolicy.text(s, msg(content = "").copy(attachments = listOf(photo))))
    }

    @Test
    fun `gateway events tell the notifier what happened`() = runBlocking {
        val state = DiscordState()
        state.update { it.copy(me = me) }
        suspend fun event(type: String, json: String): StoreEvent {
            val got = kotlinx.coroutines.CompletableDeferred<StoreEvent>()
            val job = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { got.complete(state.events.first()) }
            state.onDispatch(type, DiscordJson.parseToJsonElement(json))
            return withTimeout(2_000) { got.await() }.also { job.cancel() }
        }
        assertEquals(StoreEvent.MessageDeleted(5, 500), event("MESSAGE_DELETE", """{"id":"500","channel_id":"5"}"""))
        assertEquals("your own message reads the conversation", StoreEvent.MessageRead(5),
            event("MESSAGE_CREATE", """{"id":"501","channel_id":"5","author":{"id":"10","username":"neo"}}"""))
        assertEquals(StoreEvent.CallRinging(5), event("CALL_CREATE", """{"channel_id":"5","ringing":["10"]}"""))
        assertEquals("answered elsewhere", StoreEvent.CallStopped(5), event("CALL_UPDATE", """{"channel_id":"5","ringing":[]}"""))
    }

    @Test
    fun `a blocked user is learned from READY and from live events`() {
        val state = DiscordState()
        state.onReady(DiscordJson.decodeFromString(com.wristcord.discord.model.Ready.serializer(),
            """{"user":{"id":"10","username":"neo"},"session_id":"s","relationships":[{"id":"20","type":2},{"id":"30","type":1}]}"""))
        assertEquals(setOf(20L), state.value.blocked)
        state.onDispatch("RELATIONSHIP_REMOVE", DiscordJson.parseToJsonElement("""{"id":"20","type":2}"""))
        assertEquals(emptySet<Long>(), state.value.blocked)
    }

    @Test
    fun `catch-up looks only in changed DMs and mentions and skips what was read`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = DiscordClient(ClientIdentity.forTests(), CoroutineScope(Dispatchers.Default), apiBase = server.url("/api").toString().trimEnd('/'))
            val since = 1000L
            client.state.update {
                snapshot().copy(
                    channels = snapshot().channels
                        .put(dm, snapshot().channels[dm]!!.copy(lastMessageId = 3000))
                        .put(text, snapshot().channels[text]!!.copy(lastMessageId = 3000))
                        .put(40L, Channel(id = 40, name = "quiet", guildId = guild, lastMessageId = 3000))
                        .put(41L, Channel(id = 41, type = ChannelType.DM, lastMessageId = 500)),
                    readStates = persistentMapOf(dm to ReadState(2000, 1), text to ReadState(1000, 1), 40L to ReadState(1000, 0), 41L to ReadState(0, 0)),
                )
            }
            fun msgJson(id: Int, channel: Long) = """{"id":"$id","channel_id":"$channel","content":"m$id","author":{"id":"20","username":"trinity"},"mentions":[{"id":"10","username":"neo"}]}"""
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val channel = request.url.encodedPath.split("/")[3].toLong()
                    // Newest first, as REST pages are; 1500 is already read in the DM.
                    return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body("[${msgJson(3000, channel)},${msgJson(1500, channel)}]").build()
                }
            }
            val missed = client.missedMessages(since, dmsAndMentionsOnly = false)
            assertEquals(setOf(dm, text), missed.map { it.channelId }.toSet())
            assertEquals(2, server.requestCount)
            assertEquals(listOf(3000L), missed.filter { it.channelId == dm }.map { it.id })
            assertEquals("oldest first", listOf(1500L, 3000L), missed.filter { it.channelId == text }.map { it.id })
            assertEquals(guild, missed.first { it.channelId == text }.guildId)
        } finally {
            server.close()
        }
    }
}
