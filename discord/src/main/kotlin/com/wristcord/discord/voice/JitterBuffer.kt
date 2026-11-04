package com.wristcord.discord.voice

import java.util.TreeMap

/**
 * Playout for one remote speaker: packets go in as they arrive (any order, in bursts), and
 * every 20 ms the playback thread asks what to play next.
 *
 * - Packets are reordered by RTP sequence number, so one that overtakes another is not lost.
 * - The delay adapts: it starts at [minTarget] frames, grows a frame each time the network
 *   lets it run dry mid-speech, and shrinks back after a calm stretch. Wi-Fi in power save
 *   hands packets over in bunches; this absorbs that instead of dropping audio.
 * - A missing packet with later ones already here is lost: it is rebuilt from the next
 *   packet's Opus in-band FEC when that is here ([Next.Recover]), else concealed.
 * - Running dry right after a speaker's silence frames is the end of a sentence, not a stall:
 *   playback just stops until they talk again.
 *
 * Thread-safe: [push] runs on the network thread, [pull] on the playback thread.
 */
class JitterBuffer(
    private val minTarget: Int = 3,
    private val maxTarget: Int = 12,
    /** Never hold more than this many packets (5 s): a stalled stream cannot grow forever. */
    private val maxPackets: Int = 250,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Next {
        /** Decode and play this packet. */
        class Play(val packet: ByteArray) : Next
        /** The packet due now was lost; rebuild it from the FEC inside [following]. */
        class Recover(val following: ByteArray) : Next
        /** Nothing to rebuild from: let the decoder conceal 20 ms. */
        data object Conceal : Next
        /** Not playing (nothing buffered, or still buffering). */
        data object Silent : Next
    }

    data class Stats(
        val targetFrames: Int,
        val buffered: Int,
        val late: Long,
        val lost: Long,
        val recovered: Long,
        val concealed: Long,
        val skipped: Long,
        val underruns: Long,
        val overflows: Long,
    )

    private val packets = TreeMap<Long, ByteArray>()
    private var highest = -1L
    private var next = -1L
    private var playing = false
    private var target = minTarget
    private var dryRun = 0
    private var lastWasSilence = false
    private var firstArrival = 0L
    private var calmSince = 0L
    private var late = 0L
    private var lost = 0L
    private var recovered = 0L
    private var concealed = 0L
    private var skipped = 0L
    private var underruns = 0L
    private var overflows = 0L

    @Synchronized
    fun push(sequence: Int, payload: ByteArray) {
        val ext = extend(sequence)
        if (playing && ext < next) {
            late++
            return
        }
        if (packets.isEmpty()) firstArrival = now()
        packets[ext] = payload
        while (packets.size > maxPackets) {
            packets.pollFirstEntry()
            overflows++
        }
    }

    @Synchronized
    fun pull(): Next {
        val t = now()
        if (calmSince == 0L) calmSince = t
        if (target > minTarget && t - calmSince > CALM_MS) {
            target--
            calmSince = t
        }
        if (!playing) {
            if (packets.isEmpty()) return Next.Silent
            // Wait for the target cushion, unless this is a short burst (one word) that has
            // already waited as long as the cushion would have taken to fill.
            if (packets.size < target && t - firstArrival < target * FRAME_MS) return Next.Silent
            playing = true
            next = packets.firstKey()
            dryRun = 0
        }
        // Keep latency bounded: far behind the newest packet, jump ahead.
        while (packets.isNotEmpty() && packets.lastKey() - next >= target + MAX_EXCESS) {
            if (packets.remove(next) != null) skipped++
            next++
        }
        packets.remove(next)?.let { p ->
            next++
            dryRun = 0
            lastWasSilence = p.contentEquals(OPUS_SILENCE)
            return Next.Play(p)
        }
        if (packets.isEmpty()) {
            // Nothing at all. After their silence frames it is the end of a sentence.
            if (lastWasSilence) return stop()
            if (++dryRun > MAX_CONCEAL) {
                underruns++
                target = minOf(target + 1, maxTarget)
                calmSince = t
                return stop()
            }
            next++
            concealed++
            return Next.Conceal
        }
        // A gap with later packets already here: this one is lost.
        lost++
        next++
        val following = packets[next]
        return if (following != null) {
            recovered++
            Next.Recover(following)
        } else {
            concealed++
            Next.Conceal
        }
    }

    private fun stop(): Next {
        playing = false
        dryRun = 0
        lastWasSilence = false
        return Next.Silent
    }

    /** RTP's 16-bit sequence numbers, unwrapped into a running count. */
    private fun extend(sequence: Int): Long {
        if (highest < 0) {
            highest = sequence.toLong()
            return highest
        }
        var delta = sequence - (highest and 0xFFFF).toInt()
        if (delta < -32768) delta += 65536 else if (delta > 32768) delta -= 65536
        val ext = highest + delta
        if (ext > highest) highest = ext
        return ext
    }

    @Synchronized
    fun stats() = Stats(target, packets.size, late, lost, recovered, concealed, skipped, underruns, overflows)

    companion object {
        const val FRAME_MS = 20L
        /** Concealed frames in a row before a dry stream stops. */
        const val MAX_CONCEAL = 3
        /**
         * Frames over the target before old ones are skipped: 500 ms, so a Wi-Fi power-save
         * burst drains naturally and only delay that keeps building up is cut.
         */
        const val MAX_EXCESS = 25
        /** A calm stretch after which the target shrinks a frame. */
        const val CALM_MS = 10_000L
    }
}
