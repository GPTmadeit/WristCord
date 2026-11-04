package com.wristcord.wear.discord

import com.wristcord.discord.Snowflake
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Message
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import com.wristcord.discord.proto.ProtoWriter
import com.wristcord.wear.WristCordApp
import kotlinx.serialization.builtins.ListSerializer
import java.util.Base64

/**
 * A made-up account for looking at every screen without signing in - debug builds only
 * (`adb shell am start -n com.wristcord/.wear.MainActivity --ez com.wristcord.DEMO true`).
 * Nothing here talks to Discord.
 */
object Demo {

    const val EXTRA = "com.wristcord.DEMO"
    const val GENERAL = 1101L

    fun load(app: WristCordApp, theme: Int = 4, gradient: Int? = null) {
        val now = System.currentTimeMillis()
        fun id(minutesAgo: Long) = Snowflake.fromTimestamp(now - minutesAgo * 60_000L)
        val settings = Base64.getEncoder().encodeToString(ProtoWriter().apply {
            messageField(11) { wrappedString(1, "dnd"); messageField(2) { stringField(1, "On my wrist"); stringField(3, "⌚") } }
            messageField(13) {
                varintField(1, theme.toLong())
                if (gradient != null) messageField(3) { messageField(2) { varintField(1, gradient.toLong()) } }
            }
            messageField(14) {
                messageField(1) { bytesField(1, le(1000L)) }
                messageField(1) {
                    bytesField(1, le(3000L) + le(4000L))
                    messageField(2) { varintField(1, 9) }
                    wrappedString(3, "Projects")
                }
                messageField(1) { bytesField(1, le(2000L)) }
            }
        }.toByteArray())

        val ready = """
        {"session_id":"s-watch","user":{"id":"10","username":"neo","global_name":"Neo"},
         "users":[{"id":"20","username":"trinity","global_name":"Trinity"},{"id":"30","username":"morpheus","global_name":"Morpheus"},
                  {"id":"40","username":"tank","global_name":"Tank"},{"id":"50","username":"niobe","global_name":"Niobe"}],
         "user_settings_proto":"$settings",
         "sessions":[{"session_id":"s-phone","client_info":{"client":"mobile","os":"android"}}],
         "guilds":[
          {"id":"1000","joined_at":"2020-01-01T00:00:00+00:00","properties":{"name":"Zion","owner_id":"30"},
           "roles":[{"id":"1000","name":"@everyone","permissions":"104324673"},{"id":"1001","name":"Captains","color":15844367,"position":2,"permissions":"0"}],
           "channels":[
            {"id":"1100","type":4,"name":"Text channels","position":0},
            {"id":"$GENERAL","type":0,"name":"general","position":0,"parent_id":"1100","last_message_id":"${id(0)}"},
            {"id":"1102","type":5,"name":"announcements","position":1,"parent_id":"1100","last_message_id":"${id(90)}"},
            {"id":"1103","type":0,"name":"memes","position":2,"parent_id":"1100","last_message_id":"${id(600)}"},
            {"id":"1199","type":0,"name":"mods-only","position":3,"parent_id":"1100","permission_overwrites":[{"id":"1000","type":0,"allow":"0","deny":"1024"}]},
            {"id":"1200","type":4,"name":"Voice channels","position":1},
            {"id":"1201","type":2,"name":"Gaming","position":0,"parent_id":"1200"},
            {"id":"1202","type":2,"name":"Chill","position":1,"parent_id":"1200"}
           ]},
          {"id":"2000","joined_at":"2021-01-01T00:00:00+00:00","properties":{"name":"Nebuchadnezzar","owner_id":"30"},
           "roles":[{"id":"2000","name":"@everyone","permissions":"104324673"}],
           "channels":[{"id":"2101","type":0,"name":"bridge","position":0,"last_message_id":"${id(30)}"}]},
          {"id":"3000","joined_at":"2022-01-01T00:00:00+00:00","properties":{"name":"Pixel Watch Club","owner_id":"40"},
           "roles":[{"id":"3000","name":"@everyone","permissions":"104324673"}],
           "channels":[{"id":"3101","type":0,"name":"wear-os","position":0,"last_message_id":"${id(5)}"}]},
          {"id":"4000","joined_at":"2023-01-01T00:00:00+00:00","properties":{"name":"Rustaceans","owner_id":"40"},
           "roles":[{"id":"4000","name":"@everyone","permissions":"104324673"}],
           "channels":[{"id":"4101","type":0,"name":"help","position":0,"last_message_id":"${id(400)}"}]}
         ],
         "merged_members":[[{"user_id":"10","roles":["1001"],"nick":"The One"}],[{"user_id":"10","roles":[]}],[{"user_id":"10","roles":[]}],[{"user_id":"10","roles":[]}]],
         "private_channels":[
          {"id":"5000","type":1,"recipient_ids":["20"],"last_message_id":"${id(2)}"},
          {"id":"5001","type":3,"name":"Crew","recipient_ids":["20","30","40"],"last_message_id":"${id(300)}"},
          {"id":"5002","type":1,"recipient_ids":["50"],"last_message_id":"${id(2000)}"}
         ],
         "read_state":{"entries":[
          {"id":"$GENERAL","last_message_id":"${id(20)}","mention_count":2},
          {"id":"1102","last_message_id":"${id(90)}"},{"id":"1103","last_message_id":"${id(600)}"},
          {"id":"2101","last_message_id":"${id(60)}"},
          {"id":"3101","last_message_id":"${id(5)}"},{"id":"4101","last_message_id":"${id(400)}"},
          {"id":"5000","last_message_id":"${id(10)}","mention_count":1},{"id":"5001","last_message_id":"${id(300)}"},{"id":"5002","last_message_id":"${id(2000)}"}
         ]},
         "user_guild_settings":{"entries":[{"guild_id":"2000","muted":true}]}}
        """.trimIndent()

        val supplemental = """
        {"guilds":[{"id":"1000","voice_states":[
          {"user_id":"10","channel_id":"1201","session_id":"s-phone","self_mute":true},
          {"user_id":"20","channel_id":"1201","session_id":"x","self_deaf":true,"self_mute":true},
          {"user_id":"30","channel_id":"1201","session_id":"y","self_video":true},
          {"user_id":"40","channel_id":"1202","session_id":"z"}
        ]}]}
        """.trimIndent()

        val messages = """
        [
         {"id":"${id(0)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"20","username":"trinity","global_name":"Trinity"},
          "content":"<@10> the watch build is up — **sign in with the QR** from the phone and it just works","mentions":[{"id":"10","username":"neo"}],
          "reactions":[{"emoji":{"name":"🔥"},"count":3,"me":true},{"emoji":{"name":"👍"},"count":1,"me":false}]},
         {"id":"${id(1)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"30","username":"morpheus","global_name":"Morpheus"},
          "member":{"roles":["1001"]},"content":"> what if I told you\nthat your mute state now comes from Discord itself ||and not a notification||"},
         {"id":"${id(3)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"30","username":"morpheus","global_name":"Morpheus"},
          "member":{"roles":["1001"]},"content":"`self_mute` straight off the gateway, every theme from the real tokens"},
         {"id":"${id(8)}","channel_id":"$GENERAL","guild_id":"1000","type":19,"author":{"id":"10","username":"neo","global_name":"Neo"},
          "content":"Onyx on an OLED watch is *perfect* 🖤","message_reference":{"message_id":"${id(20)}"},
          "referenced_message":{"id":"${id(20)}","channel_id":"$GENERAL","author":{"id":"40","username":"tank","global_name":"Tank"},"content":"which theme are you running?"}},
         {"id":"${id(20)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"40","username":"tank","global_name":"Tank"},"content":"which theme are you running?"},
         {"id":"${id(25)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"50","username":"niobe","global_name":"Niobe"},
          "content":"release notes","embeds":[{"type":"rich","title":"WristCord 2.0","description":"A Discord client for your wrist: servers, DMs, voice and **your** theme.","color":5793266,"provider":{"name":"GitHub"}}]},
         {"id":"${id(40)}","channel_id":"$GENERAL","guild_id":"1000","author":{"id":"40","username":"tank","global_name":"Tank"},"content":"meeting at <t:${(now / 1000) + 7200}:R> in <#1201>"}
        ]
        """.trimIndent()

        val state = app.discord.state
        state.onReady(DiscordJson.decodeFromString(Ready.serializer(), ready))
        state.onReadySupplemental(DiscordJson.decodeFromString(ReadySupplemental.serializer(), supplemental))
        state.messages.loaded(GENERAL, DiscordJson.decodeFromString(ListSerializer(Message.serializer()), messages), older = false, pageSize = 7)
        state.onDispatch("TYPING_START", DiscordJson.parseToJsonElement("""{"channel_id":"$GENERAL","user_id":"20","timestamp":${now / 1000}}"""))
        app.account.enterDemo()
    }

    private fun le(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }
}
