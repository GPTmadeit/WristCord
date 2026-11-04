package com.wristcord.wear.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** What the audio side of a call has done, for its diagnostics. */
data class AudioStats(
    val captured: Long = 0,
    /** Captured frames that were exact digital silence: the system is withholding the mic. */
    val silentCapture: Long = 0,
    /** Frames loud enough to send (voice activity). */
    val loud: Long = 0,
    val encoded: Long = 0,
    val decoded: Long = 0,
    /** 20 ms blocks that played someone's voice. */
    val played: Long = 0,
    /** Mic level (RMS of 16-bit samples) over the last second. */
    val micLevel: Int = 0,
    val route: String = "",
    /** False while the call is silenced because Android routed it to the watch itself. */
    val routeOk: Boolean = true,
    val error: String = "",
)

/**
 * The watch end of a call: capture from the headset mic, Opus-encode, hand frames to the
 * network; and on the way back, decode each speaker, buffer a little, mix, play to the headset.
 *
 * Routing goes through the platform's call path (MODE_IN_COMMUNICATION + setCommunicationDevice),
 * which brings the system's echo cancellation and noise suppression with it.
 */
class AudioEngine(
    private val ctx: Context,
    /** Called on the capture thread with each encoded 20 ms frame while you are talking. */
    private val onFrame: (ByteArray) -> Unit,
    /** Called when you go quiet (or mute), so the network can send its silence frames. */
    private val onQuiet: () -> Unit,
) {
    @Volatile var micEnabled = true
    @Volatile var playbackEnabled = true

    /**
     * The guard behind the headset rule: false whenever Android has put the call on the watch's
     * own speaker or earpiece (the headset dropped, or something took the route), and then
     * nothing plays and nothing is sent until it is back on the headset. An unknown route does
     * not count: reports vary by device, and silencing a working call on a guess is worse.
     */
    @Volatile private var routedToHeadset = true
    @Volatile private var routeName = "no route yet"
    @Volatile private var error = ""
    @Volatile private var micLevel = 0
    private val captured = AtomicLong()
    private val silentCapture = AtomicLong()
    private val loudFrames = AtomicLong()
    private val encoded = AtomicLong()
    private val decoded = AtomicLong()
    private val played = AtomicLong()

    fun stats() = AudioStats(
        captured = captured.get(),
        silentCapture = silentCapture.get(),
        loud = loudFrames.get(),
        encoded = encoded.get(),
        decoded = decoded.get(),
        played = played.get(),
        micLevel = micLevel,
        route = routeName,
        routeOk = routedToHeadset,
        error = error,
    )

    private val _speaking = MutableStateFlow<Set<Int>>(emptySet())
    /** SSRCs heard in the last few hundred ms - drives the speaking rings in the UI. */
    val speaking: StateFlow<Set<Int>> = _speaking

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking

    @Volatile private var running = false
    private var capture: Thread? = null
    private var playback: Thread? = null
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val am = ctx.getSystemService(AudioManager::class.java)

    private class Stream {
        val decoder = OpusDecoder()
        val queue = ArrayDeque<ShortArray>()
        var lastSeq = -1
        var started = false
        var underruns = 0
        var lastHeard = 0L
    }

    fun start(device: AudioDeviceInfo) {
        if (running) return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!am.setCommunicationDevice(device)) {
            am.mode = AudioManager.MODE_NORMAL
            error("Could not route the call to ${Headset.name(device)}")
        }
        running = true
        capture = Thread(::captureLoop, "wristcord-mic").apply { start() }
        playback = Thread(::playbackLoop, "wristcord-speaker").apply { start() }
    }

    /** Re-point the call at another headset. */
    fun route(device: AudioDeviceInfo) {
        am.setCommunicationDevice(device)
    }

    fun stop() {
        running = false
        capture?.join(500)
        playback?.join(500)
        streams.values.forEach { runCatching { it.decoder.close() } }
        streams.clear()
        runCatching { am.clearCommunicationDevice() }
        am.mode = AudioManager.MODE_NORMAL
        _speaking.value = emptySet()
    }

    /** From the network thread: one decrypted Opus frame from [ssrc]. */
    fun onRemote(ssrc: Int, sequence: Int, opus: ByteArray) {
        if (!running) return
        val s = streams[ssrc] ?: runCatching { Stream() }.getOrElse {
            if (error.isEmpty()) Log.e(TAG, "no Opus decoder on this watch", it)
            error = "No Opus decoder on this watch"
            return
        }.also { streams[ssrc] = it }
        synchronized(s) {
            // Drop late duplicates; a gap of more than half the sequence space means wrap-around.
            if (s.lastSeq >= 0) {
                val delta = (sequence - s.lastSeq + 65536) % 65536
                if (delta == 0 || delta > 32768) return
            }
            s.lastSeq = sequence
            val pcm = runCatching { s.decoder.decode(opus) }.getOrNull() ?: return
            decoded.incrementAndGet()
            val stereo = if (s.decoder.outputChannels == 1) upmix(pcm) else pcm
            s.queue.addLast(stereo)
            while (s.queue.size > MAX_QUEUED) s.queue.removeFirst()
            s.lastHeard = System.currentTimeMillis()
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a call is joined.
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(OpusEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            OpusEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME_BYTES_MONO * 4),
        )
        val encoder = runCatching { OpusEncoder() }.getOrElse {
            Log.e(TAG, "no Opus encoder on this watch", it)
            error = "No Opus encoder on this watch"
            record.release()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "the microphone did not open")
            error = "The microphone did not open"
        }
        val frame = ByteArray(FRAME_BYTES_MONO)
        var hangover = 0
        var peak = 0.0
        var frames = 0
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < frame.size && running) {
                    val n = record.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame.size) continue
                captured.incrementAndGet()
                val level = rms(frame)
                if (level == 0.0) silentCapture.incrementAndGet()
                if (level > peak) peak = level
                if (++frames == 50) {
                    micLevel = peak.toInt()
                    peak = 0.0
                    frames = 0
                }
                val loud = level > VAD_THRESHOLD
                if (loud) loudFrames.incrementAndGet()
                if (loud) hangover = HANGOVER_FRAMES else if (hangover > 0) hangover--
                if (!micEnabled || !routedToHeadset || hangover == 0) {
                    if (_talking.value) {
                        _talking.value = false
                        onQuiet()
                    }
                    continue
                }
                _talking.value = true
                for (packet in encoder.encode(frame)) {
                    encoded.incrementAndGet()
                    onFrame(packet)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
        } finally {
            runCatching { record.stop() }
            record.release()
            encoder.close()
            if (_talking.value) {
                _talking.value = false
                onQuiet()
            }
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OpusEncoder.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(FRAME_SHORTS_STEREO * 2 * 4)
            .build()
        val onRoute = AudioRouting.OnRoutingChangedListener { r ->
            val d = r.routedDevice
            routeName = Headset.describe(d)
            routedToHeadset = d == null || !Headset.isBuiltIn(d)
            Log.i(TAG, "call audio routed to $routeName" + if (routedToHeadset) "" else ": silenced")
        }
        track.addOnRoutingChangedListener(onRoute, Handler(Looper.getMainLooper()))
        val mix = IntArray(FRAME_SHORTS_STEREO)
        val out = ShortArray(FRAME_SHORTS_STEREO)
        var tick = 0
        try {
            track.play()
            while (running) {
                mix.fill(0)
                val heard = HashSet<Int>()
                val now = System.currentTimeMillis()
                for ((ssrc, s) in streams) {
                    if (now - s.lastHeard > STREAM_IDLE_MS) {
                        streams.remove(ssrc)?.let { runCatching { it.decoder.close() } }
                        continue
                    }
                    val pcm = synchronized(s) {
                        // A small cushion before a speaker starts, so jitter does not crackle;
                        // a brief gap plays silence instead of re-buffering at once.
                        if (!s.started && s.queue.size >= START_FRAMES) s.started = true
                        if (!s.started) return@synchronized null
                        s.queue.pollFirst().also {
                            if (it != null) s.underruns = 0
                            else if (++s.underruns >= MAX_UNDERRUNS) s.started = false
                        }
                    } ?: continue
                    if (rmsShorts(pcm) > SPEAKING_THRESHOLD) heard += ssrc
                    for (i in 0 until minOf(pcm.size, mix.size)) mix[i] += pcm[i].toInt()
                }
                val audible = playbackEnabled && routedToHeadset
                for (i in out.indices) out[i] = if (audible) mix[i].coerceIn(-32768, 32767).toShort() else 0
                if (audible && heard.isNotEmpty()) played.incrementAndGet()
                track.write(out, 0, out.size) // blocks: this is what paces the loop at real time
                if (++tick % 5 == 0 || heard != _speaking.value) _speaking.value = heard
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback failed", t)
        } finally {
            track.removeOnRoutingChangedListener(onRoute)
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun rms(pcm16le: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val v = ((pcm16le[i + 1].toInt() shl 8) or (pcm16le[i].toInt() and 0xFF)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        return sqrt(sum / (pcm16le.size / 2))
    }

    private fun rmsShorts(pcm: ShortArray): Double {
        var sum = 0.0
        for (v in pcm) sum += v.toDouble() * v
        return sqrt(sum / pcm.size)
    }

    private fun upmix(mono: ShortArray): ShortArray = ShortArray(mono.size * 2) { mono[it / 2] }

    private companion object {
        const val TAG = "WristCord/Audio"
        const val FRAME_BYTES_MONO = OpusEncoder.FRAME_SAMPLES * 2
        const val FRAME_SHORTS_STEREO = OpusEncoder.FRAME_SAMPLES * 2
        const val MAX_QUEUED = 6
        const val START_FRAMES = 2
        /** 100 ms without a frame before a speaker re-buffers. */
        const val MAX_UNDERRUNS = 5
        const val STREAM_IDLE_MS = 30_000L
        /** About -45 dBFS: speech on a headset mic, not breathing or a fan. */
        const val VAD_THRESHOLD = 180.0
        const val SPEAKING_THRESHOLD = 250.0
        /** Keep sending for 300 ms after the last loud frame so words are not clipped. */
        const val HANGOVER_FRAMES = 15
    }
}
