package com.wristcord.discord

import com.wristcord.discord.model.Attachment
import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.StickerItem
import com.wristcord.discord.model.User

/** CDN URLs, sized for a watch: never ask for more pixels than the screen can show. */
object Cdn {

    fun avatar(user: User, size: Int = 64): String {
        val hash = user.avatar ?: return defaultAvatar(user)
        val ext = if (hash.startsWith("a_")) "webp?animated=false&" else "png?"
        return "${Discord.CDN}/avatars/${user.id}/$hash.${ext}size=${pow2(size)}"
    }

    fun avatar(userId: Long, hash: String?, size: Int = 64): String =
        if (hash == null) "${Discord.CDN}/embed/avatars/${((userId ushr 22) % 6)}.png"
        else "${Discord.CDN}/avatars/$userId/$hash.png?size=${pow2(size)}"

    /** Pomelo usernames (discriminator "0") index by id; legacy ones by discriminator. */
    fun defaultAvatar(user: User): String {
        val d = user.discriminator?.toIntOrNull() ?: 0
        val index = if (d == 0) ((user.id ushr 22) % 6).toInt() else d % 5
        return "${Discord.CDN}/embed/avatars/$index.png"
    }

    fun guildIcon(guildId: Long, hash: String?, size: Int = 64): String? =
        hash?.let { "${Discord.CDN}/icons/$guildId/$it.png?size=${pow2(size)}" }

    fun groupIcon(channel: Channel, size: Int = 64): String? =
        channel.icon?.let { "${Discord.CDN}/channel-icons/${channel.id}/$it.png?size=${pow2(size)}" }

    fun emoji(id: Long, animated: Boolean, size: Int = 48): String =
        "${Discord.CDN}/emojis/$id.webp?size=${pow2(size)}&quality=lossless" + if (animated) "&animated=true" else ""

    fun emoji(e: Emoji, size: Int = 48): String? = e.id?.let { emoji(it, e.animated, size) }

    fun sticker(s: StickerItem, size: Int = 160): String? = when (s.formatType) {
        1, 2 -> "${Discord.MEDIA_PROXY}/stickers/${s.id}.png?size=${pow2(size)}"
        4 -> "${Discord.MEDIA_PROXY}/stickers/${s.id}.gif?size=${pow2(size)}"
        else -> null // Lottie: no renderer on the watch, the name is shown instead.
    }

    /**
     * Attachment preview through Discord's media proxy at the size it will be drawn. The proxy
     * URL is signed (`ex`, `is`, `hm`), so the sizing parameters are appended, not replaced.
     */
    fun preview(a: Attachment, maxPx: Int): String {
        val base = a.proxyUrl ?: a.url
        val (w, h) = fit(a.width, a.height, maxPx)
        val sep = if ('?' in base) '&' else '?'
        return "$base${sep}format=webp&width=$w&height=$h"
    }

    fun proxied(url: String?, width: Int?, height: Int?, maxPx: Int): String? {
        url ?: return null
        if (!url.contains("discordapp")) return url
        val (w, h) = fit(width, height, maxPx)
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}width=$w&height=$h"
    }

    fun fit(width: Int?, height: Int?, maxPx: Int): Pair<Int, Int> {
        val w = width ?: maxPx
        val h = height ?: maxPx
        if (w <= maxPx && h <= maxPx) return w to h
        val scale = maxPx.toFloat() / maxOf(w, h)
        return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
    }

    /** The CDN only serves power-of-two sizes from 16 to 4096. */
    private fun pow2(size: Int): Int {
        var s = 16
        while (s < size && s < 4096) s *= 2
        return s
    }
}
