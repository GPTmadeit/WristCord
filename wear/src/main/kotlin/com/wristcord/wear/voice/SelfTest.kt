package com.wristcord.wear.voice

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Debug builds only (`--ez com.wristcord.SELFTEST true`): proves the on-device half of voice
 * works without a real call - the native DAVE library, XChaCha20, and the platform Opus codec.
 * Results go to logcat under WristCord/SelfTest.
 */
object SelfTest {
    private const val TAG = "WristCord/SelfTest"

    fun run(ctx: Context): Boolean {
        var ok = true
        fun check(name: String, block: () -> String) {
            val r = runCatching(block)
            ok = ok && r.isSuccess
            Log.i(TAG, if (r.isSuccess) "PASS $name: ${r.getOrNull()}" else "FAIL $name: ${r.exceptionOrNull()}")
        }
        check("dave library") {
            check(NativeDave.available) { "not loaded" }
            "loaded, max protocol v${NativeDave.maxProtocolVersion}"
        }
        check("dave key package") {
            val s = NativeDave.create(1, 123456789012345678L, 223456789012345678L) ?: error("session not created")
            val kp = s.createKeyPackage() ?: error("no key package: ${s.lastError}")
            s.close()
            "MLS key package of ${kp.size} bytes"
        }
        check("xchacha20-poly1305") {
            val key = ByteArray(32) { it.toByte() }
            val c = NativeDave.xchacha(key)
            val nonce = ByteArray(24).also { it[0] = 1 }
            val sealed = c.seal(nonce, byteArrayOf(9, 9), byteArrayOf(1, 2, 3))
            val opened = c.open(nonce, byteArrayOf(9, 9), sealed) ?: error("did not verify")
            check(opened.contentEquals(byteArrayOf(1, 2, 3))) { "round trip changed the data" }
            check(c.open(nonce, byteArrayOf(9, 8), sealed) == null) { "accepted a forged AAD" }
            "seal/open ok, ${sealed.size} bytes with tag"
        }
        check("opus round trip") {
            val enc = OpusEncoder()
            val dec = OpusDecoder()
            val packets = ArrayList<ByteArray>()
            var phase = 0.0
            // Paced like the microphone (one frame per 20 ms): the codec runs on its own
            // thread, so what is still in flight at the end is its real latency.
            repeat(50) {
                val frame = ByteBuffer.allocate(OpusEncoder.FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(OpusEncoder.FRAME_SAMPLES) {
                    frame.putShort((sin(phase) * 8000).toInt().toShort())
                    phase += 2 * PI * 440 / OpusEncoder.SAMPLE_RATE
                }
                packets += enc.encode(frame.array())
                Thread.sleep(OpusEncoder.FRAME_US / 1000)
            }
            val inFlight = 50 - packets.size
            var energy = 0.0
            var samples = 0
            for (p in packets) dec.decode(p)?.let { pcm -> for (v in pcm) energy += v.toDouble() * v; samples += pcm.size }
            enc.close()
            dec.close()
            val rms = if (samples > 0) sqrt(energy / samples) else 0.0
            check(inFlight <= 3) { "encoder is ${inFlight * 20} ms behind" }
            check(rms > 1000) { "decoded audio too quiet (rms $rms)" }
            "${packets.size} packets, avg ${packets.sumOf { it.size } / packets.size} B, encoder lag ${inFlight * 20} ms, decoded rms ${rms.toInt()}"
        }
        check("headset gate") {
            val h = Headset.current(ctx)
            "headset ${h?.let(Headset::name) ?: "none (joining is blocked)"}"
        }
        Log.i(TAG, if (ok) "ALL PASS" else "SOME FAILED")
        return ok
    }
}
