package com.wristcord.wear.voice

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through the platform's own codec (Codec2's software Opus, in every Android since 10),
 * so the watch carries no audio codec of its own. 48 kHz, 20 ms frames, as Discord sends them.
 */
class OpusEncoder(channels: Int = 1, bitrate: Int = 64_000) : AutoCloseable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * channels)
            setInteger(MediaFormat.KEY_COMPLEXITY, 5)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** One 20 ms frame of 16-bit PCM in; zero or more Opus packets out. */
    fun encode(pcm: ByteArray, length: Int = pcm.size): List<ByteArray> {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex >= 0) {
            val buf = codec.getInputBuffer(inIndex)!!
            buf.clear()
            buf.put(pcm, 0, length)
            codec.queueInputBuffer(inIndex, 0, length, ptsUs, 0)
            ptsUs += FRAME_US
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        var out: MutableList<ByteArray>? = null
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            // The first buffer is the codec config (OpusHead); RTP carries bare packets.
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buf = codec.getOutputBuffer(outIndex)!!
                val packet = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(packet)
                (out ?: ArrayList<ByteArray>(1).also { out = it }).add(packet)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
        return out ?: emptyList()
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val FRAME_US = 20_000L
        private const val TIMEOUT_US = 10_000L
    }
}

/** One decoder per remote speaker. Always stereo out, the way Discord encodes. */
class OpusDecoder : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    @Volatile var outputChannels = 2
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OpusEncoder.SAMPLE_RATE, 2).apply {
            setByteBuffer("csd-0", opusHead(channels = 2))
            setByteBuffer("csd-1", le64(0)) // pre-skip, ns
            setByteBuffer("csd-2", le64(80_000_000)) // seek pre-roll, ns
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** One Opus packet in; interleaved 16-bit PCM out (or null if the codec had nothing yet). */
    fun decode(packet: ByteArray): ShortArray? {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return null
        val buf = codec.getInputBuffer(inIndex)!!
        buf.clear()
        buf.put(packet)
        codec.queueInputBuffer(inIndex, 0, packet.size, ptsUs, 0)
        ptsUs += OpusEncoder.FRAME_US
        var pcm: ShortArray? = null
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (pcm == null) TIMEOUT_US else 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    outputChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                outIndex < 0 -> break
                else -> {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset)
                        val shorts = ShortArray(info.size / 2)
                        out.asShortBuffer().get(shorts)
                        pcm = if (pcm == null) shorts else pcm + shorts
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
        return pcm
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** RFC 7845 identification header, what Android's Opus decoder wants as csd-0. */
        fun opusHead(channels: Int): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(channels.toByte())
            putShort(0)
            putInt(OpusEncoder.SAMPLE_RATE)
            putShort(0)
            put(0)
            flip()
        }

        fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }
    }
}
