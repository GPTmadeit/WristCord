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

    /** Debug builds only (`--ez com.wristcord.MICTEST true`): which mic sources deliver sound. */
    @android.annotation.SuppressLint("MissingPermission")
    fun mics(ctx: Context) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val sources = listOf(
            "VOICE_COMMUNICATION" to android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "MIC" to android.media.MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "CAMCORDER" to android.media.MediaRecorder.AudioSource.CAMCORDER,
            "UNPROCESSED" to android.media.MediaRecorder.AudioSource.UNPROCESSED,
        )
        for (mode in listOf(android.media.AudioManager.MODE_NORMAL, android.media.AudioManager.MODE_IN_COMMUNICATION)) {
            am.mode = mode
            for ((name, source) in sources) {
                val r = runCatching {
                    val rec = android.media.AudioRecord(source, 48_000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, 48_000)
                    val buf = ShortArray(960)
                    var frames = 0
                    var zero = 0
                    var sum = 0.0
                    var n = 0L
                    rec.startRecording()
                    repeat(50) {
                        val got = rec.read(buf, 0, buf.size)
                        if (got > 0) {
                            frames++
                            var e = 0.0
                            for (i in 0 until got) e += buf[i].toDouble() * buf[i]
                            if (e == 0.0) zero++
                            sum += e
                            n += got
                        }
                    }
                    val dev = rec.routedDevice?.let { "${it.type}:${it.productName}" }
                    rec.stop()
                    rec.release()
                    "frames=$frames zero=$zero rms=${if (n > 0) sqrt(sum / n).toInt() else 0} device=$dev"
                }
                Log.i(TAG, "mic mode=$mode $name: ${r.getOrElse { "failed: $it" }}")
            }
        }
        am.mode = android.media.AudioManager.MODE_NORMAL
        Log.i(TAG, "mic test done")
    }

    /**
     * Debug builds only (`--ez com.wristcord.ENGINETEST true`): the real call audio engine on the
     * connected headset, without Discord. A soft 2 s tone goes through the same decode-and-mix
     * path as a speaker in a call, and the mic is measured while it plays.
     */
    fun engine(ctx: Context) {
        val headset = Headset.current(ctx) ?: run { Log.i(TAG, "engine: no headset"); return }
        // The tone is encoded before the engine starts, so only the engine's own codecs run.
        val packets = ArrayList<ByteArray>()
        OpusEncoder(channels = 1).use { enc ->
            var phase = 0.0
            repeat(100) {
                val frame = ByteBuffer.allocate(OpusEncoder.FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(OpusEncoder.FRAME_SAMPLES) {
                    frame.putShort((sin(phase) * 3000).toInt().toShort())
                    phase += 2 * PI * 440 / OpusEncoder.SAMPLE_RATE
                }
                packets += enc.encode(frame.array())
                Thread.sleep(OpusEncoder.FRAME_US / 1000)
            }
        }
        val engine = AudioEngine(ctx, onFrame = {}, onQuiet = {})
        try {
            kotlinx.coroutines.runBlocking { engine.start(headset) }
            Log.i(TAG, "engine: path=${engine.path?.label} headset=${Headset.name(headset)}, ${packets.size} packets")
            packets.forEachIndexed { seq, p ->
                engine.onRemote(1234, seq, p)
                Thread.sleep(OpusEncoder.FRAME_US / 1000)
            }
            Thread.sleep(500)
            val s = engine.stats()
            Log.i(TAG, "engine: $s")
            Log.i(TAG, if (s.played > 50 && s.routeOk && s.captured > 50) "engine PASS" else "engine FAIL")
        } catch (t: Throwable) {
            Log.e(TAG, "engine FAIL", t)
        } finally {
            engine.stop()
        }
    }
}
