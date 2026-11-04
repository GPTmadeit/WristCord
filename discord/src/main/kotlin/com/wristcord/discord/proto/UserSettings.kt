package com.wristcord.discord.proto

import java.util.Base64

/** Discord's four themes, by the name its own UI shows. */
enum class Theme {
    DARK, ASH, ONYX, LIGHT;

    companion object {
        /**
         * The proto enum's names are historical and do not match the UI. This follows
         * PROTO_THEME_MAP_WEB_REFRESH in the Android app: 1 DARK is Ash, 3 DARKER is Dark,
         * 4 MIDNIGHT is Onyx, and unset means Dark.
         */
        fun fromProto(value: Int): Theme = when (value) {
            1 -> ASH
            2 -> LIGHT
            4 -> ONYX
            else -> DARK
        }
    }
}

data class CustomStatus(
    val text: String = "",
    val emojiId: Long = 0,
    val emojiName: String = "",
    val expiresAtMs: Long = 0,
) {
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        (text.isNotBlank() || emojiName.isNotBlank() || emojiId != 0L) && (expiresAtMs == 0L || expiresAtMs > nowMs)
}

data class GuildFolder(
    val guildIds: List<Long>,
    /** Null for the implicit "folder" Discord uses for a guild outside any folder. */
    val id: Long?,
    val name: String?,
    val color: Long?,
)

/** The subset of PreloadedUserSettings (settings-proto type 1) WristCord uses. */
data class UserSettings(
    val theme: Theme = Theme.DARK,
    /** ClientThemeSettings.background_gradient_preset_id; Nitro gradient, null when unset. */
    val gradientPreset: Int? = null,
    /** "online", "idle", "dnd", "invisible", or "" when never set. */
    val status: String = "",
    val customStatus: CustomStatus? = null,
    val folders: List<GuildFolder> = emptyList(),
    val guildPositions: List<Long> = emptyList(),
    val locale: String = "",
) {
    /** Guild order as Discord's server list shows it: folders first-to-last, positions as fallback. */
    fun guildOrder(): List<Long> = if (folders.isNotEmpty()) folders.flatMap { it.guildIds } else guildPositions

    companion object {
        // PreloadedUserSettings field numbers (internalBinaryRead switch in
        // preloaded_user_settings.tsx).
        private const val F_STATUS = 11
        private const val F_LOCALIZATION = 12
        private const val F_APPEARANCE = 13
        private const val F_GUILD_FOLDERS = 14

        fun parseBase64(b64: String?): UserSettings =
            if (b64.isNullOrBlank()) UserSettings() else parse(Base64.getDecoder().decode(b64))

        fun parse(bytes: ByteArray): UserSettings {
            var s = UserSettings()
            val r = ProtoReader(bytes)
            while (true) {
                val (f, w) = r.next() ?: break
                s = when {
                    f == F_APPEARANCE && w == 2 -> appearance(r.message(), s)
                    f == F_STATUS && w == 2 -> status(r.message(), s)
                    f == F_GUILD_FOLDERS && w == 2 -> folders(r.message(), s)
                    f == F_LOCALIZATION && w == 2 -> localization(r.message(), s)
                    else -> s.also { r.skip(w) }
                }
            }
            return s
        }

        /** Merge for USER_SETTINGS_PROTO_UPDATE with partial = true: present fields win. */
        fun merge(base: UserSettings, partialBytes: ByteArray): UserSettings {
            val r = ProtoReader(partialBytes)
            var s = base
            while (true) {
                val (f, w) = r.next() ?: break
                s = when {
                    f == F_APPEARANCE && w == 2 -> appearance(r.message(), s)
                    f == F_STATUS && w == 2 -> status(r.message(), s)
                    f == F_GUILD_FOLDERS && w == 2 -> folders(r.message(), s.copy(folders = emptyList(), guildPositions = emptyList()))
                    f == F_LOCALIZATION && w == 2 -> localization(r.message(), s)
                    else -> s.also { r.skip(w) }
                }
            }
            return s
        }

        private fun appearance(r: ProtoReader, s: UserSettings): UserSettings {
            var out = s
            while (true) {
                val (f, w) = r.next() ?: break
                out = when {
                    f == 1 && w == 0 -> out.copy(theme = Theme.fromProto(r.varint().toInt()))
                    f == 3 && w == 2 -> {
                        val m = r.message()
                        var preset: Int? = null
                        while (true) {
                            val (cf, cw) = m.next() ?: break
                            if (cf == 2 && cw == 2) preset = m.wrappedVarint()?.toInt() else m.skip(cw)
                        }
                        out.copy(gradientPreset = preset)
                    }
                    else -> out.also { r.skip(w) }
                }
            }
            return out
        }

        private fun status(r: ProtoReader, s: UserSettings): UserSettings {
            var out = s
            while (true) {
                val (f, w) = r.next() ?: break
                out = when {
                    f == 1 && w == 2 -> out.copy(status = r.wrappedString().orEmpty())
                    f == 2 && w == 2 -> out.copy(customStatus = customStatus(r.message()))
                    else -> out.also { r.skip(w) }
                }
            }
            return out
        }

        private fun customStatus(r: ProtoReader): CustomStatus {
            var c = CustomStatus()
            while (true) {
                val (f, w) = r.next() ?: break
                c = when {
                    f == 1 && w == 2 -> c.copy(text = r.string())
                    f == 2 && w == 1 -> c.copy(emojiId = r.fixed64())
                    f == 3 && w == 2 -> c.copy(emojiName = r.string())
                    f == 4 && w == 1 -> c.copy(expiresAtMs = r.fixed64())
                    else -> c.also { r.skip(w) }
                }
            }
            return c
        }

        private fun folders(r: ProtoReader, s: UserSettings): UserSettings {
            val folders = ArrayList<GuildFolder>()
            val positions = ArrayList<Long>()
            while (true) {
                val (f, w) = r.next() ?: break
                when {
                    f == 1 && w == 2 -> folders += folder(r.message())
                    f == 2 -> positions += r.packedFixed64(w)
                    else -> r.skip(w)
                }
            }
            return s.copy(folders = folders, guildPositions = positions)
        }

        private fun folder(r: ProtoReader): GuildFolder {
            var ids = emptyList<Long>()
            var id: Long? = null
            var name: String? = null
            var color: Long? = null
            while (true) {
                val (f, w) = r.next() ?: break
                when {
                    f == 1 -> ids = ids + r.packedFixed64(w)
                    f == 2 && w == 2 -> id = r.wrappedVarint()
                    f == 3 && w == 2 -> name = r.wrappedString()
                    f == 4 && w == 2 -> color = r.wrappedVarint()
                    else -> r.skip(w)
                }
            }
            return GuildFolder(ids, id, name?.takeIf { it.isNotBlank() }, color)
        }

        private fun localization(r: ProtoReader, s: UserSettings): UserSettings {
            var out = s
            while (true) {
                val (f, w) = r.next() ?: break
                out = if (f == 1 && w == 2) out.copy(locale = r.wrappedString().orEmpty()) else out.also { r.skip(w) }
            }
            return out
        }

        /**
         * A partial PreloadedUserSettings that changes only the online status. PATCHed to
         * /users/@me/settings-proto/1, Discord merges it and every device follows.
         */
        fun statusPatch(status: String): String {
            val bytes = ProtoWriter().apply {
                messageField(F_STATUS) { wrappedString(1, status) }
            }.toByteArray()
            return Base64.getEncoder().encodeToString(bytes)
        }

        /** A partial settings proto setting (or, with all blanks, clearing) the custom status. */
        fun customStatusPatch(text: String, emojiName: String = "", emojiId: Long = 0, expiresAtMs: Long = 0): String {
            val bytes = ProtoWriter().apply {
                messageField(F_STATUS) {
                    messageField(2) {
                        if (text.isNotEmpty()) stringField(1, text)
                        if (emojiId != 0L) fixed64Field(2, emojiId)
                        if (emojiName.isNotEmpty()) stringField(3, emojiName)
                        if (expiresAtMs != 0L) fixed64Field(4, expiresAtMs)
                    }
                }
            }.toByteArray()
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}

/** The subset of FrecencyUserSettings (settings-proto type 2): what you react with most. */
data class Frecency(
    /** Most used first. Unicode emoji come back as Discord names ("thumbsup"), custom ones as ids. */
    val emojis: List<String> = emptyList(),
    val reactionEmojis: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
) {
    companion object {
        // frecency_user_settings.tsx: favorite_emojis = 5, emoji_frecency = 6,
        // emoji_reaction_frecency = 13. FrecencyItem: total_uses = 1, frecency = 3, score = 4.
        fun parseBase64(b64: String?): Frecency {
            if (b64.isNullOrBlank()) return Frecency()
            val r = ProtoReader(Base64.getDecoder().decode(b64))
            var out = Frecency()
            while (true) {
                val (f, w) = r.next() ?: break
                out = when {
                    f == 5 && w == 2 -> out.copy(favorites = stringList(r.message()))
                    f == 6 && w == 2 -> out.copy(emojis = ranked(r.message()))
                    f == 13 && w == 2 -> out.copy(reactionEmojis = ranked(r.message()))
                    else -> out.also { r.skip(w) }
                }
            }
            return out
        }

        private fun stringList(r: ProtoReader): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val (f, w) = r.next() ?: break
                if (f == 1 && w == 2) out += r.string() else r.skip(w)
            }
            return out
        }

        /** EmojiFrecency { map<string, FrecencyItem> emojis = 1 }, sorted by score. */
        private fun ranked(r: ProtoReader): List<String> {
            val scored = ArrayList<Pair<String, Long>>()
            while (true) {
                val (f, w) = r.next() ?: break
                if (f != 1 || w != 2) {
                    r.skip(w); continue
                }
                val entry = r.message()
                var key = ""
                var score = 0L
                var uses = 0L
                while (true) {
                    val (ef, ew) = entry.next() ?: break
                    when {
                        ef == 1 && ew == 2 -> key = entry.string()
                        ef == 2 && ew == 2 -> {
                            val item = entry.message()
                            while (true) {
                                val (itf, itw) = item.next() ?: break
                                when {
                                    itf == 1 && itw == 0 -> uses = item.varint()
                                    itf == 3 && itw == 0 -> score = maxOf(score, item.varint())
                                    itf == 4 && itw == 0 -> score = maxOf(score, item.varint())
                                    else -> item.skip(itw)
                                }
                            }
                        }
                        else -> entry.skip(ew)
                    }
                }
                if (key.isNotEmpty()) scored += key to (if (score != 0L) score else uses)
            }
            return scored.sortedByDescending { it.second }.map { it.first }
        }
    }
}
