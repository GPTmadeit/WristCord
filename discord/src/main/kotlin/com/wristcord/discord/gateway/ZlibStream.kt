package com.wristcord.discord.gateway

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * `compress=zlib-stream`: one zlib context for the life of the connection, and every gateway
 * payload ends with a `Z_SYNC_FLUSH` marker (`00 00 FF FF`). Frames without the marker are
 * partial and are buffered until it arrives.
 *
 * Not thread-safe - the socket delivers frames one at a time.
 */
class ZlibStream {

    private val inflater = Inflater()
    private val pending = ByteArrayOutputStream()
    private val chunk = ByteArray(64 * 1024)

    /** Returns the decompressed payload once a complete one has arrived, else null. */
    fun feed(frame: ByteArray): ByteArray? {
        pending.write(frame)
        if (!endsWithSuffix(frame)) return null
        val input = pending.toByteArray()
        pending.reset()

        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size * 6)
        while (true) {
            val n = inflater.inflate(chunk)
            if (n > 0) out.write(chunk, 0, n)
            if (n == 0 && (inflater.needsInput() || inflater.finished())) break
        }
        return out.toByteArray()
    }

    fun close() {
        inflater.end()
    }

    private fun endsWithSuffix(b: ByteArray): Boolean {
        if (b.size < 4) return pending.size() >= 4 && pendingEndsWithSuffix()
        val n = b.size
        return b[n - 4] == 0.toByte() && b[n - 3] == 0.toByte() &&
            b[n - 2] == 0xFF.toByte() && b[n - 1] == 0xFF.toByte()
    }

    private fun pendingEndsWithSuffix(): Boolean {
        val all = pending.toByteArray()
        val n = all.size
        return all[n - 4] == 0.toByte() && all[n - 3] == 0.toByte() &&
            all[n - 2] == 0xFF.toByte() && all[n - 1] == 0xFF.toByte()
    }
}
