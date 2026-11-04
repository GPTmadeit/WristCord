package com.wristcord.wear.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through libopus (native/dave, bundled by opusic-sys). 48 kHz, 20 ms frames, as Discord
 * sends them. Android's MediaCodec Opus did this up to 2.0.0-beta.3: on Wear OS 17 it crashed
 * natively with more than one codec busy, and handed decoded frames back in bursts.
 *
 * Every call is synchronized, so a stream closed on one thread while another decodes into it
 * gets a null instead of a freed native decoder.
 */
class OpusEncoder(private val channels: Int = 1, bitrate: Int = 64_000) : AutoCloseable {
    private var handle = NativeOpus.encoderNew(channels, bitrate).also { check(it != 0L) { "libopus refused the encoder" } }
    private val frame = ShortArray(FRAME_SAMPLES * channels)

    /** One 20 ms frame of 16-bit little-endian PCM in; its Opus packet out. */
    @Synchronized
    fun encode(pcm: ByteArray, length: Int = pcm.size): List<ByteArray> {
        if (handle == 0L || length < frame.size * 2) return emptyList()
        ByteBuffer.wrap(pcm, 0, frame.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame)
        return listOfNotNull(NativeOpus.encode(handle, frame))
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeOpus.encoderDestroy(handle)
        handle = 0L
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val FRAME_US = 20_000L
    }
}

/** One decoder per remote speaker. Always stereo out, the way Discord encodes. */
class OpusDecoder : AutoCloseable {
    private var handle = NativeOpus.decoderNew(CHANNELS).also { check(it != 0L) { "libopus refused the decoder" } }
    private val buffer = ShortArray(MAX_FRAME_SAMPLES * CHANNELS)

    val outputChannels = CHANNELS

    /** One Opus packet in; interleaved 16-bit PCM out, or null if it would not decode. */
    @Synchronized
    fun decode(packet: ByteArray): ShortArray? = run(packet)

    /** A plausible 20 ms in place of a packet that never arrived (Opus loss concealment). */
    @Synchronized
    fun conceal(): ShortArray? = run(null)

    /** The lost 20 ms just before [following], rebuilt from the in-band FEC it carries. */
    @Synchronized
    fun recover(following: ByteArray): ShortArray? = run(following, fec = true)

    private fun run(packet: ByteArray?, fec: Boolean = false): ShortArray? {
        if (handle == 0L) return null
        val samples = NativeOpus.decode(handle, packet, buffer, fec)
        return if (samples > 0) buffer.copyOf(samples * CHANNELS) else null
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeOpus.decoderDestroy(handle)
        handle = 0L
    }

    private companion object {
        const val CHANNELS = 2
        /** 120 ms, the longest Opus frame. */
        const val MAX_FRAME_SAMPLES = 5_760
    }
}

/** The JNI side of [OpusEncoder] and [OpusDecoder] (native/dave/src/opus.rs). */
internal object NativeOpus {
    init {
        System.loadLibrary("wristcord_dave")
    }

    fun encoderNew(channels: Int, bitrate: Int): Long = nativeEncoderNew(channels, bitrate)
    fun encode(handle: Long, pcm: ShortArray): ByteArray? = nativeEncode(handle, pcm)
    fun encoderDestroy(handle: Long) = nativeEncoderDestroy(handle)
    fun decoderNew(channels: Int): Long = nativeDecoderNew(channels)
    fun decode(handle: Long, packet: ByteArray?, out: ShortArray, fec: Boolean = false): Int = nativeDecode(handle, packet, out, fec)
    fun decoderDestroy(handle: Long) = nativeDecoderDestroy(handle)

    @JvmStatic private external fun nativeEncoderNew(channels: Int, bitrate: Int): Long
    @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray?
    @JvmStatic private external fun nativeEncoderDestroy(handle: Long)
    @JvmStatic private external fun nativeDecoderNew(channels: Int): Long
    @JvmStatic private external fun nativeDecode(handle: Long, packet: ByteArray?, out: ShortArray, fec: Boolean): Int
    @JvmStatic private external fun nativeDecoderDestroy(handle: Long)
}
