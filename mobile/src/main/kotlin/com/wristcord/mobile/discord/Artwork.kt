package com.wristcord.mobile.discord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Pulls the server / channel artwork off Discord's own call notification so the watch can show
 * it behind the controls, the way YouTube Music shows album art behind its transport.
 *
 * This is the same picture the notification already displays on the phone - we are re-using an
 * image Discord itself published, not fetching anything from Discord's servers.
 */
object Artwork {

    /** Small on purpose: a Wearable DataItem tops out around 100 KB including everything else. */
    private const val EDGE_PX = 160
    private const val QUALITY = 82

    data class Encoded(val bytes: ByteArray, val hash: String) {
        // Generated equals/hashCode would compare the ByteArray by identity; the hash is the
        // identity we actually care about, and it is what the watch compares too.
        override fun equals(other: Any?): Boolean = other is Encoded && other.hash == hash
        override fun hashCode(): Int = hash.hashCode()
    }

    fun of(ctx: Context, voice: VoiceNotification?): Encoded? {
        val icon = voice?.sbn?.notification?.getLargeIcon() ?: return null
        val drawable: Drawable = runCatching { icon.loadDrawable(ctx) }.getOrNull() ?: return null
        val bitmap = runCatching { toBitmap(drawable) }.getOrNull() ?: return null

        val out = ByteArrayOutputStream()
        val ok = runCatching {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
        }.getOrDefault(false)
        if (!ok || out.size() == 0) return null

        val bytes = out.toByteArray()
        return Encoded(bytes = bytes, hash = sha256(bytes))
    }

    private fun toBitmap(drawable: Drawable): Bitmap {
        val source = (drawable as? BitmapDrawable)?.bitmap
        if (source != null && !source.isRecycled) return scale(source)

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: EDGE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: EDGE_PX
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rendered)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return scale(rendered)
    }

    private fun scale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= EDGE_PX) return source
        val factor = EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(12)
            .joinToString("") { "%02x".format(it) }
}
