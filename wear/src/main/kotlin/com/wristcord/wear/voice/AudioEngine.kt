package com.wristcord.wear.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.wristcord.discord.voice.JitterBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** How a call reaches the headset. */
enum class AudioPath(val label: String) {
    /** Bluetooth call audio (HFP or LE Audio): the headset's own speakers and mic. */
    CALL("headset call audio"),
    /**
     * Media audio (A2DP) to the headset, and the watch's own mic. Wear OS refuses call audio to
     * a classic Bluetooth headset while the watch is connected to its phone: the watch is then a
     * hands-free device for the phone's calls ("rejected SCO since HFPC is connected"), and that
     * blocks every app, Telecom included.
     */
    MEDIA("headset media audio, watch mic"),
}

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
    val micSource: String = "",
    val route: String = "",
    val path: String = "",
    /** False while the call is silenced because Android routed it to the watch itself. */
    val routeOk: Boolean = true,
    val error: String = "",
    /** The jitter buffer's playout delay (the largest over the speakers), in ms. */
    val bufferMs: Int = 0,
    val lost: Long = 0,
    /** Lost packets rebuilt from the next packet's Opus FEC. */
    val recovered: Long = 0,
    val concealed: Long = 0,
    val late: Long = 0,
    /** Times the network let a speaker run dry mid-sentence. */
    val networkGaps: Long = 0,
    val skipped: Long = 0,
    /** Times Android's audio output ran out of audio: the watch fell behind. */
    val outputUnderruns: Int = 0,
    /** Longest wait between two packets from one speaker in the last 5 s. */
    val arrivalGapMs: Long = 0,
    val decodeUs: Long = 0,
    val encodeUs: Long = 0,
)

/**
 * The watch end of a call: capture, Opus-encode and hand frames to the network; on the way
 * back, a jitter buffer per speaker, decode, mix, play to the headset.
 *
 * Decoding happens at playout, on the audio-priority playback thread: the network thread only
 * queues packets, so a busy moment (you talking: encode, encrypt, send) can no longer delay
 * incoming audio into gaps.
 */
class AudioEngine(
    private val ctx: Context,
    /** Called on the capture thread with each encoded 20 ms frame and its capture frame number. */
    private val onFrame: (ByteArray, Long) -> Unit,
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
    @Volatile private var micSource = ""
    @Volatile private var track: AudioTrack? = null
    private val captured = AtomicLong()
    private val silentCapture = AtomicLong()
    private val loudFrames = AtomicLong()
    private val encoded = AtomicLong()
    private val decoded = AtomicLong()
    private val played = AtomicLong()
    private val decodeNs = AtomicLong()
    private val encodeNs = AtomicLong()
    @Volatile private var gapWindowStart = 0L
    @Volatile private var gapWindowMax = 0L
    @Volatile private var gapLastWindow = 0L

    fun stats(): AudioStats {
        val jitter = streams.values.map { it.jitter.stats() }
        val enc = encoded.get()
        val dec = decoded.get()
        return AudioStats(
            captured = captured.get(),
            silentCapture = silentCapture.get(),
            loud = loudFrames.get(),
            encoded = enc,
            decoded = dec,
            played = played.get(),
            micLevel = micLevel,
            micSource = micSource,
            route = routeName,
            path = path?.label.orEmpty(),
            routeOk = routedToHeadset,
            error = error,
            bufferMs = (jitter.maxOfOrNull { it.targetFrames } ?: 0) * FRAME_MS,
            lost = jitter.sumOf { it.lost },
            recovered = jitter.sumOf { it.recovered },
            concealed = jitter.sumOf { it.concealed },
            late = jitter.sumOf { it.late },
            networkGaps = jitter.sumOf { it.underruns },
            skipped = jitter.sumOf { it.skipped } + jitter.sumOf { it.overflows },
            outputUnderruns = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0),
            arrivalGapMs = maxOf(gapLastWindow, gapWindowMax),
            decodeUs = if (dec > 0) decodeNs.get() / dec / 1_000 else 0,
            encodeUs = if (enc > 0) encodeNs.get() / enc / 1_000 else 0,
        )
    }

    private val _speaking = MutableStateFlow<Set<Int>>(emptySet())
    /** SSRCs heard in the last few hundred ms - drives the speaking rings in the UI. */
    val speaking: StateFlow<Set<Int>> = _speaking

    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking

    @Volatile private var running = false
    /** Chosen in [start]: call audio when Android brings it up, media audio otherwise. */
    @Volatile var path: AudioPath? = null
        private set
    private var mediaOutput: AudioDeviceInfo? = null
    private var focus: AudioFocusRequest? = null
    private var capture: Thread? = null
    private var playback: Thread? = null
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val am = ctx.getSystemService(AudioManager::class.java)

    /** One remote speaker. The decoder and [pending] are only touched by the playback thread. */
    private class Stream {
        val decoder = OpusDecoder()
        val jitter = JitterBuffer()
        /** Decoded audio not played yet, when a packet held more than 20 ms. */
        val pending = ArrayDeque<ShortArray>()
        @Volatile var lastArrival = 0L
        @Volatile var lastHeard = 0L
    }

    /**
     * Routes the call to [device]: as call audio if Android brings that up, otherwise as media
     * audio with the watch's mic. Never to the watch's speaker: with neither, the join fails.
     */
    suspend fun start(device: AudioDeviceInfo) {
        if (running) return
        path = if (callRoute(device)) {
            AudioPath.CALL
        } else {
            mediaOutput = Headset.mediaOutput(ctx, device)
                ?: error("${Headset.name(device)} cannot take call or media audio from the watch")
            AudioPath.MEDIA
        }
        Log.i(TAG, "call audio path: ${path!!.label} (${Headset.name(device)})")
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener { }
            .build()
            .also { am.requestAudioFocus(it) }
        running = true
        capture = Thread(::captureLoop, "wristcord-mic").apply { start() }
        playback = Thread(::playbackLoop, "wristcord-speaker").apply { start() }
    }

    /**
     * Android can accept setCommunicationDevice and then fail to bring the link up (it always
     * does for a classic headset on a watch paired with a phone), so the route only counts once
     * Android reports it.
     */
    private suspend fun callRoute(device: AudioDeviceInfo): Boolean {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (am.setCommunicationDevice(device)) {
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < CALL_ROUTE_WAIT_MS) {
                delay(100)
                val now = am.communicationDevice
                val elapsed = SystemClock.elapsedRealtime() - start
                if (now != null && now.type == device.type && elapsed >= CALL_ROUTE_SETTLE_MS) return true
                if (now != null && Headset.isBuiltIn(now) && elapsed >= CALL_ROUTE_SETTLE_MS) break
            }
            Log.i(TAG, "Android did not bring up call audio on ${Headset.name(device)}; using media audio")
        }
        runCatching { am.clearCommunicationDevice() }
        am.mode = AudioManager.MODE_NORMAL
        return false
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (path == AudioPath.MEDIA) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Re-point the call at another headset. Media audio follows Android's active headset. */
    fun route(device: AudioDeviceInfo) {
        if (path == AudioPath.CALL) am.setCommunicationDevice(device)
    }

    fun stop() {
        running = false
        capture?.join(500)
        playback?.join(500)
        streams.values.forEach { runCatching { it.decoder.close() } }
        streams.clear()
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
        runCatching { am.clearCommunicationDevice() }
        am.mode = AudioManager.MODE_NORMAL
        _speaking.value = emptySet()
    }

    /** From the network thread: one decrypted Opus frame from [ssrc]. Only queues it. */
    fun onRemote(ssrc: Int, sequence: Int, opus: ByteArray) {
        if (!running) return
        val s = streams[ssrc] ?: runCatching { Stream() }.getOrElse {
            if (error.isEmpty()) Log.e(TAG, "no Opus decoder", it)
            error = "No Opus decoder"
            return
        }.also { streams[ssrc] = it }
        val t = SystemClock.elapsedRealtime()
        if (t - gapWindowStart > GAP_WINDOW_MS) {
            gapLastWindow = gapWindowMax
            gapWindowMax = 0
            gapWindowStart = t
        }
        // Gaps across a pause in speech are not network trouble: only count those inside speech.
        val gap = t - s.lastArrival
        if (s.lastArrival != 0L && gap < SPEECH_PAUSE_MS && gap > gapWindowMax) gapWindowMax = gap
        s.lastArrival = t
        s.lastHeard = System.currentTimeMillis()
        s.jitter.push(sequence, opus)
    }

    /** The next 20 ms of [s], decoded now on the playback thread; null when it is not playing. */
    private fun next(s: Stream): ShortArray? {
        s.pending.pollFirst()?.let { return it }
        val started = System.nanoTime()
        val pcm = when (val n = s.jitter.pull()) {
            is JitterBuffer.Next.Play -> s.decoder.decode(n.packet)
            is JitterBuffer.Next.Recover -> s.decoder.recover(n.following) ?: s.decoder.conceal()
            JitterBuffer.Next.Conceal -> s.decoder.conceal()
            JitterBuffer.Next.Silent -> return null
        } ?: return null
        decodeNs.addAndGet(System.nanoTime() - started)
        decoded.incrementAndGet()
        val stereo = if (s.decoder.outputChannels == 1) upmix(pcm) else pcm
        if (stereo.size <= FRAME_SHORTS_STEREO) return stereo
        // A packet longer than 20 ms: play it out block by block.
        var at = FRAME_SHORTS_STEREO
        while (at < stereo.size) {
            s.pending.addLast(stereo.copyOfRange(at, minOf(at + FRAME_SHORTS_STEREO, stereo.size)))
            at += FRAME_SHORTS_STEREO
        }
        return stereo.copyOfRange(0, FRAME_SHORTS_STEREO)
    }

    /**
     * Mic sources, best first for the path. A source can come back as pure digital silence:
     * the Pixel Watch 4 gives VOICE_COMMUNICATION nothing but zeros unless a Bluetooth call
     * link is up, so a source that stays exactly zero for a second is swapped for the next.
     */
    private fun micSources(): List<Int> = when (path) {
        AudioPath.CALL -> listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        else -> listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a call is joined.
    private fun openMic(source: Int): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(OpusEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(source, OpusEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES_MONO * 4))
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?.also { it.startRecording() }
    }.getOrNull()

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val encoder = runCatching { OpusEncoder() }.getOrElse {
            Log.e(TAG, "no Opus encoder", it)
            error = "No Opus encoder"
            return
        }
        val sources = micSources()
        var sourceIndex = 0
        var record = openMic(sources[0])
        if (record == null) {
            Log.e(TAG, "the microphone did not open")
            error = "The microphone did not open"
            encoder.close()
            return
        }
        micSource = sourceName(sources[0])
        val frame = ByteArray(FRAME_BYTES_MONO)
        val previous = ByteArray(FRAME_BYTES_MONO)
        var previousIndex = -1L
        var hangover = 0
        var peak = 0.0
        var frames = 0
        var zeros = 0
        var floor = 0.0
        var gain = 1.0

        fun send(pcm: ByteArray, index: Long) {
            val started = System.nanoTime()
            val packets = encoder.encode(pcm)
            encodeNs.addAndGet(System.nanoTime() - started)
            for (packet in packets) {
                encoded.incrementAndGet()
                onFrame(packet, index)
            }
        }

        try {
            while (running) {
                var read = 0
                while (read < frame.size && running) {
                    val n = record!!.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame.size) continue
                val index = captured.incrementAndGet() - 1
                val level = rms(frame)
                if (level == 0.0) {
                    silentCapture.incrementAndGet()
                    if (++zeros >= SILENT_SOURCE_FRAMES && sourceIndex < sources.lastIndex) {
                        val next = openMic(sources[sourceIndex + 1])
                        if (next != null) {
                            runCatching { record!!.stop() }
                            record!!.release()
                            record = next
                            sourceIndex++
                            micSource = sourceName(sources[sourceIndex])
                            Log.w(TAG, "mic gave only silence; switched to $micSource")
                        }
                        zeros = 0
                    }
                } else {
                    zeros = 0
                }
                if (level > peak) peak = level
                if (++frames == 50) {
                    micLevel = peak.toInt()
                    peak = 0.0
                    frames = 0
                }
                // Voice activity over the room's noise floor: the floor drops at once and rises
                // slowly, so speech barely lifts it. A wrist mic is far quieter than a headset's.
                floor = if (floor == 0.0 || level < floor) level else floor + (level - floor) * FLOOR_RISE
                val loud = level > maxOf(VAD_MIN, floor * VAD_OVER_FLOOR)
                if (loud) loudFrames.incrementAndGet()
                if (loud) hangover = HANGOVER_FRAMES else if (hangover > 0) hangover--
                // Media mode's mic is on the wrist, at arm's length: lift speech toward a steady
                // level, cutting at once when it gets loud and climbing back slowly.
                if (path == AudioPath.MEDIA) {
                    if (loud) {
                        val want = (AGC_TARGET / level).coerceIn(1.0, AGC_MAX)
                        gain = if (want < gain) want else gain + (want - gain) * AGC_RISE
                    }
                    amplify(frame, gain)
                }
                if (!micEnabled || !routedToHeadset || hangover == 0) {
                    if (_talking.value) {
                        _talking.value = false
                        onQuiet()
                    }
                    frame.copyInto(previous)
                    previousIndex = index
                    continue
                }
                if (!_talking.value) {
                    _talking.value = true
                    // The frame before voice activity fired often holds the start of the word.
                    if (previousIndex == index - 1) send(previous, previousIndex)
                }
                send(frame, index)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
        } finally {
            runCatching { record?.stop() }
            record?.release()
            encoder.close()
            if (_talking.value) {
                _talking.value = false
                onQuiet()
            }
        }
    }

    private fun sourceName(source: Int) = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "call mic"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "speech mic"
        MediaRecorder.AudioSource.MIC -> "plain mic"
        else -> "mic $source"
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Call audio wants low latency; media audio over A2DP already adds ~200 ms and gains
        // nothing from it, so it gets a deeper output buffer that rides out a slow moment.
        val media = path == AudioPath.MEDIA
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OpusEncoder.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(if (media) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(FRAME_SHORTS_STEREO * 2 * if (media) MEDIA_BUFFER_FRAMES else CALL_BUFFER_FRAMES)
            .build()
        this.track = track
        val onRoute = AudioRouting.OnRoutingChangedListener { r ->
            val d = r.routedDevice
            routeName = Headset.describe(d)
            routedToHeadset = d == null || !Headset.isBuiltIn(d)
            Log.i(TAG, "call audio routed to $routeName" + if (routedToHeadset) "" else ": silenced")
        }
        track.addOnRoutingChangedListener(onRoute, Handler(Looper.getMainLooper()))
        mediaOutput?.let { track.setPreferredDevice(it) }
        val mix = IntArray(FRAME_SHORTS_STEREO)
        val out = ShortArray(FRAME_SHORTS_STEREO)
        var tick = 0
        try {
            track.play()
            // Fill the output buffer with silence first, so the loop is paced by real time from
            // its first tick instead of racing through the jitter buffer while the buffer fills.
            repeat(if (media) MEDIA_BUFFER_FRAMES else CALL_BUFFER_FRAMES) { track.write(out, 0, out.size) }
            while (running) {
                mix.fill(0)
                val heard = HashSet<Int>()
                val now = System.currentTimeMillis()
                for ((ssrc, s) in streams) {
                    if (now - s.lastHeard > STREAM_IDLE_MS) {
                        streams.remove(ssrc)?.let { runCatching { it.decoder.close() } }
                        continue
                    }
                    val pcm = next(s) ?: continue
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
            this.track = null
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

    /** Scales 16-bit little-endian PCM in place, saturating instead of wrapping. */
    private fun amplify(pcm16le: ByteArray, gain: Double) {
        if (gain <= 1.001) return
        var i = 0
        while (i + 1 < pcm16le.size) {
            val v = ((pcm16le[i + 1].toInt() shl 8) or (pcm16le[i].toInt() and 0xFF)).toShort()
            val g = (v * gain).toInt().coerceIn(-32768, 32767)
            pcm16le[i] = g.toByte()
            pcm16le[i + 1] = (g shr 8).toByte()
            i += 2
        }
    }

    private fun rmsShorts(pcm: ShortArray): Double {
        var sum = 0.0
        for (v in pcm) sum += v.toDouble() * v
        return sqrt(sum / pcm.size)
    }

    private fun upmix(mono: ShortArray): ShortArray = ShortArray(mono.size * 2) { mono[it / 2] }

    private companion object {
        const val TAG = "WristCord/Audio"
        const val FRAME_MS = 20
        const val FRAME_BYTES_MONO = OpusEncoder.FRAME_SAMPLES * 2
        const val FRAME_SHORTS_STEREO = OpusEncoder.FRAME_SAMPLES * 2
        /** How long Android gets to bring up headset call audio, and how long it must hold. */
        const val CALL_ROUTE_WAIT_MS = 2_500L
        const val CALL_ROUTE_SETTLE_MS = 600L
        /** Output buffer: 80 ms on call audio, 160 ms on media audio. */
        const val CALL_BUFFER_FRAMES = 4
        const val MEDIA_BUFFER_FRAMES = 8
        const val STREAM_IDLE_MS = 30_000L
        /** Window for the longest-arrival-gap statistic, and what counts as a pause in speech. */
        const val GAP_WINDOW_MS = 5_000L
        const val SPEECH_PAUSE_MS = 400L
        /** Voice activity: this far over the noise floor, and never below [VAD_MIN]. */
        const val VAD_OVER_FLOOR = 3.0
        const val VAD_MIN = 60.0
        /** Per-frame rise of the noise floor estimate (falls are immediate). */
        const val FLOOR_RISE = 0.002
        /** Wrist-mic gain in media mode: toward this speech level, at most +12 dB. */
        const val AGC_TARGET = 3_000.0
        const val AGC_MAX = 4.0
        const val AGC_RISE = 0.05
        /** One second of exact digital silence and the next mic source is tried. */
        const val SILENT_SOURCE_FRAMES = 50
        const val SPEAKING_THRESHOLD = 250.0
        /** Keep sending for 300 ms after the last loud frame so words are not clipped. */
        const val HANGOVER_FRAMES = 15
    }
}
