package com.wristcord.wear.ui

/**
 * Discord stores unicode emoji in its frecency proto by shortcode ("thumbsup"), the common
 * GitHub/Slack names. This maps the ones people actually react with; anything rarer is skipped
 * rather than shown as a name.
 */
object EmojiNames {

    /** What the reaction row offers before Discord's frecency has loaded. */
    val defaults = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "🙏", "✅")

    fun toUnicode(name: String): String? = if (name.any { it.code > 0x2000 }) name else table[name]

    private val table: Map<String, String> by lazy {
        RAW.trimIndent().lineSequence().flatMap { line ->
            val parts = line.trim().split(' ')
            if (parts.size < 2) emptySequence() else parts.drop(1).asSequence().map { it to parts[0] }
        }.toMap()
    }

    // emoji name [aliases...]
    private const val RAW = """
        👍 thumbsup +1 thumbs_up
        👎 thumbsdown -1 thumbs_down
        ❤️ heart red_heart
        🧡 orange_heart
        💛 yellow_heart
        💚 green_heart
        💙 blue_heart
        💜 purple_heart
        🖤 black_heart
        🤍 white_heart
        💔 broken_heart
        💕 two_hearts
        😂 joy
        🤣 rofl rolling_on_the_floor_laughing
        😭 sob
        😢 cry
        😮 open_mouth
        😯 hushed
        😲 astonished
        😱 scream
        😀 grinning
        😃 smiley
        😄 smile
        😁 grin
        😆 laughing satisfied
        😅 sweat_smile
        😊 blush
        🙂 slight_smile slightly_smiling_face
        🙃 upside_down upside_down_face
        😉 wink
        😍 heart_eyes
        🥰 smiling_face_with_3_hearts
        😘 kissing_heart
        😎 sunglasses
        🤔 thinking
        🤨 face_with_raised_eyebrow
        😐 neutral_face
        😑 expressionless
        🙄 rolling_eyes
        😏 smirk
        😴 sleeping
        🥱 yawning_face
        😤 triumph
        😡 rage
        😠 angry
        🤬 face_with_symbols_over_mouth
        🥺 pleading_face
        🥹 face_holding_back_tears
        😳 flushed
        🤯 exploding_head
        🥳 partying_face
        🤩 star_struck
        🤗 hugging hugging_face
        🤭 face_with_hand_over_mouth
        🫡 saluting_face
        🫠 melting_face
        🤡 clown
        💀 skull
        ☠️ skull_crossbones
        👀 eyes
        👋 wave
        👏 clap
        🙌 raised_hands
        🙏 pray
        🤝 handshake
        💪 muscle
        🤞 crossed_fingers
        ✌️ v
        👌 ok_hand
        🤌 pinched_fingers
        🫶 heart_hands
        👉 point_right
        👈 point_left
        ☝️ point_up
        🔥 fire
        ✨ sparkles
        ⭐ star
        🌟 star2
        💯 100
        ✅ white_check_mark
        ✔️ heavy_check_mark
        ❌ x
        ❗ exclamation
        ❓ question
        ⚠️ warning
        🎉 tada
        🎊 confetti_ball
        🎁 gift
        🏆 trophy
        🎮 video_game
        🎧 headphones
        🎵 musical_note
        🎶 notes
        📌 pushpin
        📎 paperclip
        💡 bulb
        🔔 bell
        🚀 rocket
        ⚡ zap
        🌈 rainbow
        ☀️ sunny
        🌙 crescent_moon
        ☕ coffee
        🍕 pizza
        🍔 hamburger
        🍺 beer
        🍻 beers
        🥂 champagne_glass
        🐐 goat
        🐶 dog
        🐱 cat
        🦊 fox
        🐸 frog
        🐍 snake
        🦀 crab
        🤖 robot
        👻 ghost
        💩 poop
        🫃 pregnant_man
        😬 grimacing
        🤤 drooling_face
        😋 yum
        🤪 zany_face
        😇 innocent
        🥲 smiling_face_with_tear
        😔 pensive
        😞 disappointed
        😩 weary
        😫 tired_face
        🤷 shrug person_shrugging
        🤦 facepalm person_facepalming
        🙈 see_no_evil
        🙉 hear_no_evil
        🙊 speak_no_evil
        💤 zzz
        💬 speech_balloon
        👑 crown
        💎 gem
        ⌚ watch
        📱 iphone mobile_phone
        💻 computer
    """
}
