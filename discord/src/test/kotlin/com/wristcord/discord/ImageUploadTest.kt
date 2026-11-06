package com.wristcord.discord

import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Overwrite
import com.wristcord.discord.model.Role
import com.wristcord.discord.model.User
import com.wristcord.discord.state.GuildInfo
import com.wristcord.discord.state.Permissions
import com.wristcord.discord.state.Snapshot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageUploadTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val channel = 555L

    private lateinit var client: DiscordClient

    @Before
    fun setUp() {
        server.start()
        client = DiscordClient(
            ClientIdentity.forTests(),
            CoroutineScope(Dispatchers.Default),
            apiBase = server.url("/api").toString().trimEnd('/'),
        )
        client.state.update { it.copy(me = User(id = 10, username = "neo")) }
        client.start("the-token")
        client.gateway.disconnect(resumable = false)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun slotResponse() = json(
        """{"attachments":[{"id":0,"upload_url":"${server.url("/bucket/signed?sig=abc")}","upload_filename":"uploads/xyz/picture.jpg"}]}""",
    )

    private val echo = """{"id":"900","channel_id":"$channel","content":"look","author":{"id":"10","username":"neo"},"attachments":[]}"""

    @Test
    fun `an image goes slot then bucket then message`() = runBlocking {
        // Discord echoes the nonce, which is what clears the pending row.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "PUT" -> MockResponse.Builder().code(200).build()
                request.url.encodedPath.endsWith("/attachments") -> slotResponse()
                else -> {
                    val nonce = DiscordJson.parseToJsonElement(request.body!!.utf8()).jsonObject["nonce"]
                    json(echo.replaceFirst("{", "{\"nonce\":$nonce,"))
                }
            }
        }
        val image = temp.newFile("picture.jpg").apply { writeBytes(ByteArray(1234) { 7 }) }

        assertTrue(client.sendImage(channel, image, " look "))

        val slot = server.takeRequest()
        assertEquals("POST", slot.method)
        assertEquals("/api/channels/$channel/attachments", slot.url.encodedPath)
        assertEquals("the-token", slot.headers["Authorization"])
        val file = DiscordJson.parseToJsonElement(slot.body!!.utf8()).jsonObject["files"]!!.jsonArray[0].jsonObject
        assertEquals("picture.jpg", (file["filename"] as JsonPrimitive).content)
        assertEquals("1234", (file["file_size"] as JsonPrimitive).content)

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/bucket/signed", put.url.encodedPath)
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertEquals(1234L, put.body!!.size.toLong())
        assertNull("the storage bucket must not see the token", put.headers["Authorization"])

        val message = server.takeRequest()
        assertEquals("/api/channels/$channel/messages", message.url.encodedPath)
        val sent = DiscordJson.parseToJsonElement(message.body!!.utf8()).jsonObject
        assertEquals("look", (sent["content"] as JsonPrimitive).content)
        val attachment = sent["attachments"]!!.jsonArray[0].jsonObject
        assertEquals("0", (attachment["id"] as JsonPrimitive).content)
        assertEquals("picture.jpg", (attachment["filename"] as JsonPrimitive).content)
        assertEquals("uploads/xyz/picture.jpg", (attachment["uploaded_filename"] as JsonPrimitive).content)

        assertFalse("the temporary file goes once Discord has it", image.exists())
        assertTrue(client.state.messages.peek(channel)!!.pending.isEmpty())
    }

    @Test
    fun `a notification reply works before READY and reads the conversation`() = runBlocking {
        client.state.update { it.copy(me = null) }
        server.enqueue(json(echo))
        val sent = client.replyDirect(channel, 1000L, " on my way ")
        assertEquals(900L, sent.id)
        val body = DiscordJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("on my way", (body["content"] as JsonPrimitive).content)
        assertEquals(900L, client.state.value.readStates[channel]?.lastMessageId)
    }

    @Test
    fun `mark as read from a notification acks that message`() = runBlocking {
        server.enqueue(json("{}"))
        client.markReadDirect(channel, 900L, 1000L)
        assertEquals("/api/channels/$channel/messages/900/ack", server.takeRequest().url.encodedPath)
        assertEquals(900L, client.state.value.readStates[channel]?.lastMessageId)
    }

    @Test
    fun `an image with no caption is allowed`() = runBlocking {
        server.enqueue(slotResponse())
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(json(echo))
        assertTrue(client.sendImage(channel, temp.newFile("a.jpg").apply { writeBytes(byteArrayOf(1)) }))
    }

    @Test
    fun `a failed upload keeps the file and a retryable row`() = runBlocking {
        server.enqueue(slotResponse())
        server.enqueue(MockResponse.Builder().code(503).build())
        val image = temp.newFile("picture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(client.sendImage(channel, image))

        assertTrue(image.exists())
        val row = client.state.messages.peek(channel)!!.pending.single()
        assertNotNull(row.failed)
        assertEquals(image, row.image)
        assertEquals("no message is posted for a failed upload", 2, server.requestCount)
    }

    @Test
    fun `a missing file fails without calling Discord`() = runBlocking {
        val gone = temp.root.resolve("gone.jpg")
        assertFalse(client.sendImage(channel, gone))
        assertEquals(0, server.requestCount)
        assertNotNull(client.state.messages.peek(channel)!!.pending.single().failed)
    }

    @Test
    fun `attach permission follows channel overwrites and threads follow the parent`() {
        val guild = 1000L
        val everyone = Role(id = guild, name = "@everyone", permissions = Permissions.ATTACH_FILES or Permissions.VIEW_CHANNEL)
        val denied = Channel(id = 2, type = 0, guildId = guild, overwrites = listOf(Overwrite(id = guild, type = 0, allow = 0, deny = Permissions.ATTACH_FILES)))
        val open = Channel(id = 3, type = 0, guildId = guild)
        val thread = Channel(id = 4, type = ChannelType.PUBLIC_THREAD, guildId = guild, parentId = 2)
        val dm = Channel(id = 5, type = ChannelType.DM)
        val snap = Snapshot(
            me = User(id = 10, username = "neo"),
            guilds = persistentMapOf(guild to GuildInfo(guild, "Zion", null, 30L, persistentMapOf(guild to everyone), persistentListOf(), emptyList(), null, 0L)),
            channels = persistentMapOf(2L to denied, 3L to open, 4L to thread, 5L to dm),
        )
        assertFalse(snap.canAttachFiles(2))
        assertTrue(snap.canAttachFiles(3))
        assertFalse(snap.canAttachFiles(4))
        assertTrue(snap.canAttachFiles(5))
        assertFalse(snap.canAttachFiles(999))
    }
}
