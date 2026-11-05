package com.wristcord.wear.discord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picture into the file that actually gets sent. Every source goes through here, which
 * keeps uploads small enough for a watch's network and, because the image is re-encoded, leaves
 * EXIF (including any location) behind. An animated image is sent as its first frame.
 */
object ImageOutbox {
    private const val TAG = "WristCord/Images"
    private const val MAX_EDGE = 1280
    private const val JPEG_QUALITY = 85
    /** A failed send stays retryable this long; pending rows only live in memory anyway. */
    private const val KEEP_MS = 24 * 60 * 60_000L

    fun fromUri(ctx: Context, uri: Uri): File? = decode(ctx, ImageDecoder.createSource(ctx.contentResolver, uri))

    fun fromFile(ctx: Context, file: File): File? = decode(ctx, ImageDecoder.createSource(file))

    fun fromBitmap(ctx: Context, bitmap: Bitmap): File? = write(ctx, bitmap)

    private fun decode(ctx: Context, source: ImageDecoder.Source): File? = try {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_EDGE.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
        try { write(ctx, bitmap) } finally { bitmap.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read image", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "image too large to decode", e)
        null
    }

    private fun write(ctx: Context, bitmap: Bitmap): File? {
        val dir = ctx.cacheDir.resolve("outbox").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val png = bitmap.hasAlpha()
        val file = File(dir, "wristcord-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
        val written = file.outputStream().use { out ->
            if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (written) return file
        file.delete()
        return null
    }
}
