package com.wristcord.wear.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** libopus through native/dave (host build): what the watch now encodes and decodes with. */
class OpusCodecTest {

    @Test
    fun `mono speech in, stereo out at the same level, one frame per packet`() {
        val enc = OpusEncoder(channels = 1)
        val dec = OpusDecoder()
        var phase = 0.0
        var energy = 0.0
        var samples = 0
        repeat(50) { i ->
            val frame = ByteBuffer.allocate(OpusEncoder.FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(OpusEncoder.FRAME_SAMPLES) {
                frame.putShort((sin(phase) * 8000).toInt().toShort())
                phase += 2 * PI * 440 / OpusEncoder.SAMPLE_RATE
            }
            val packets = enc.encode(frame.array())
            assertEquals("one packet per 20 ms frame", 1, packets.size)
            val pcm = dec.decode(packets[0])
            assertNotNull(pcm)
            assertEquals("20 ms of stereo per packet, never a burst", OpusEncoder.FRAME_SAMPLES * 2, pcm!!.size)
            if (i >= 5) for (v in pcm) { energy += v.toDouble() * v; samples++ }
        }
        val rms = sqrt(energy / samples)
        assertTrue("decoded level $rms", rms in 4500.0..7000.0) // 8000 peak sine = 5657 RMS
        enc.close()
        dec.close()
    }

    @Test
    fun `a lost packet is concealed with 20 ms, and a closed codec is safe`() {
        val dec = OpusDecoder()
        assertEquals(OpusEncoder.FRAME_SAMPLES * 2, dec.conceal()!!.size)
        dec.close()
        assertEquals(null, dec.decode(byteArrayOf(0x78, 1, 2)))
        val enc = OpusEncoder()
        enc.close()
        assertTrue(enc.encode(ByteArray(OpusEncoder.FRAME_SAMPLES * 2)).isEmpty())
    }
}
