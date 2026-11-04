package com.wristcord.discord

import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import com.wristcord.discord.proto.ProtoWriter
import com.wristcord.discord.proto.Theme
import com.wristcord.discord.state.DiscordState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * A READY shaped the way capabilities 30719 make Discord send it: deduplicated users,
 * CLIENT_STATE_V2 guild properties, merged members, versioned read states, settings proto.
 */
class StateTest {

    private val settings = Base64.getEncoder().encodeToString(ProtoWriter().apply {
        messageField(13) { varintField(1, 1) } // proto DARK = Ash
        messageField(14) {
            messageField(1) { bytesField(1, le(2000L)) }
            messageField(1) { bytesField(1, le(1000L)) }
        }
    }.toByteArray())

    private fun le(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }

    private val readyJson = """
    {"t":"READY","s":1,"op":0,"d":{
      "v":9,"session_id":"s-watch","resume_gateway_url":"wss://gateway-us-east1-b.discord.gg",
      "user":{"id":"10","username":"neo","global_name":"Neo","avatar":null,"discriminator":"0"},
      "users":[{"id":"20","username":"trinity","global_name":"Trinity","avatar":"abc"}],
      "user_settings_proto":"$settings",
      "sessions":[{"session_id":"s-phone","client_info":{"client":"mobile","os":"android","version":0},"status":"online"}],
      "guilds":[
        {"id":"1000","joined_at":"2024-01-01T00:00:00.000000+00:00","data_mode":"full",
         "properties":{"name":"Zion","icon":"zz","owner_id":"99"},
         "roles":[{"id":"1000","name":"@everyone","permissions":"1024","position":0,"color":0}],
         "channels":[
           {"id":"1100","type":4,"name":"Text","position":0,"permission_overwrites":[]},
           {"id":"1101","type":0,"name":"general","position":0,"parent_id":"1100","last_message_id":"1201","permission_overwrites":[]},
           {"id":"1102","type":0,"name":"secret","position":1,"parent_id":"1100","last_message_id":"1300","permission_overwrites":[{"id":"1000","type":0,"allow":"0","deny":"1024"}]},
           {"id":"1103","type":2,"name":"Voice","position":2,"parent_id":"1100","permission_overwrites":[]}
         ]},
        {"id":"2000","properties":{"name":"Nebuchadnezzar","icon":null,"owner_id":"10"},
         "roles":[{"id":"2000","name":"@everyone","permissions":"0"}],
         "channels":[{"id":"2101","type":0,"name":"bridge","position":0,"last_message_id":"2500"}]}
      ],
      "merged_members":[[{"user_id":"10","roles":[],"nick":"The One"}],[{"user_id":"10","roles":[]}]],
      "private_channels":[{"id":"3000","type":1,"recipient_ids":["20"],"last_message_id":"3500"}],
      "read_state":{"version":5,"partial":false,"entries":[
        {"id":"1101","last_message_id":"1200","mention_count":2},
        {"id":"2101","last_message_id":"2500","mention_count":0},
        {"id":"3000","last_message_id":"3400","mention_count":1}
      ]},
      "user_guild_settings":{"version":3,"partial":false,"entries":[{"guild_id":"2000","muted":true,"channel_overrides":[]}]}
    }}
    """.trimIndent()

    private fun load(): DiscordState {
        val frame = DiscordJson.parseToJsonElement(readyJson).jsonObject
        val ready = DiscordJson.decodeFromJsonElement(Ready.serializer(), frame["d"]!!)
        return DiscordState().apply { onReady(ready) }
    }

    @Test
    fun `ready builds guilds in folder order with hidden channels filtered`() {
        val s = load().value
        assertEquals("Neo", s.me?.displayName)
        assertEquals(listOf(2000L, 1000L), s.guildsInOrder().map { it.id })
        assertEquals("Zion", s.guilds[1000]?.name)
        assertEquals("The One", s.guilds[1000]?.myNick)
        val visible = s.channelsOf(1000).map { it.name }
        assertTrue("general" in visible)
        assertFalse("secret" in visible)
        assertEquals(Theme.ASH, s.settings.theme)
    }

    @Test
    fun `unread and mention badges`() {
        val s = load().value
        assertTrue(s.isUnread(1101))
        assertEquals(2, s.guildBadge(1000).mentions)
        assertTrue(s.guildBadge(1000).unread)
        // Hidden channels never count, even with newer messages.
        assertFalse(s.channelsOf(1000).any { it.id == 1102L })
        // Muted server: up to date anyway, and muted would suppress the dot.
        assertFalse(s.guildBadge(2000).unread)
        assertTrue(s.dmBadge().unread)
        assertEquals("Trinity", s.channelTitle(s.channels[3000]!!))
    }

    @Test
    fun `message create, ack from another device, and voice on the phone`() {
        val st = load()
        st.onDispatch("MESSAGE_CREATE", DiscordJson.parseToJsonElement(
            """{"id":"1210","channel_id":"1101","guild_id":"1000","author":{"id":"20","username":"trinity"},"content":"hi <@10>","mentions":[{"id":"10","username":"neo"}]}"""
        ))
        assertEquals(3, st.value.mentions(1101))
        assertEquals(1210L, st.value.channels[1101]?.lastMessageId)

        st.onDispatch("MESSAGE_ACK", DiscordJson.parseToJsonElement("""{"channel_id":"1101","message_id":"1210","version":6}"""))
        assertFalse(st.value.isUnread(1101))
        assertEquals(0, st.value.mentions(1101))

        st.onReadySupplemental(DiscordJson.decodeFromString(ReadySupplemental.serializer(),
            """{"guilds":[{"id":"1000","voice_states":[{"user_id":"10","channel_id":"1103","session_id":"s-phone","self_mute":true,"self_deaf":false}]}]}"""))
        val v = st.value.myVoice
        assertNotNull(v)
        assertTrue(v!!.selfMute)
        assertEquals("android", st.value.sessionFor(v)?.clientInfo?.os)

        st.onDispatch("VOICE_STATE_UPDATE", DiscordJson.parseToJsonElement(
            """{"user_id":"10","guild_id":"1000","channel_id":null,"session_id":"s-phone"}"""))
        assertNull(st.value.myVoice)
    }

    @Test
    fun `settings proto update applies a partial`() {
        val st = load()
        val partial = Base64.getEncoder().encodeToString(ProtoWriter().apply { messageField(13) { varintField(1, 4) } }.toByteArray())
        st.onDispatch("USER_SETTINGS_PROTO_UPDATE", DiscordJson.parseToJsonElement(
            """{"settings":{"type":1,"proto":"$partial"},"partial":true}"""))
        assertEquals(Theme.ONYX, st.value.settings.theme)
        assertEquals(listOf(2000L, 1000L), st.value.guildsInOrder().map { it.id })
    }

    @Suppress("unused")
    private fun JsonObject.d() = this["d"]
}
