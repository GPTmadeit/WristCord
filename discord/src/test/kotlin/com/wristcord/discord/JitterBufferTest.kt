package com.wristcord.discord

import com.wristcord.discord.voice.JitterBuffer
import com.wristcord.discord.voice.JitterBuffer.Next
import com.wristcord.discord.voice.OPUS_SILENCE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private var clock = 0L
    private val jb = JitterBuffer(minTarget = 3, maxTarget = 12, now = { clock })

    private fun packet(n: Int) = byteArrayOf(0x78, n.toByte())
    private fun tick(): Next = jb.pull().also { clock += 20 }
    private fun played(n: Next): Int = (n as Next.Play).packet[1].toInt()

    @Test
    fun `buffers the target before playing, then plays in order`() {
        jb.push(100, packet(0))
        jb.push(101, packet(1))
        assertTrue(tick() is Next.Silent)
        jb.push(102, packet(2))
        assertEquals(0, played(tick()))
        assertEquals(1, played(tick()))
        assertEquals(2, played(tick()))
    }

    @Test
    fun `out of order packets are reordered, not dropped`() {
        listOf(10, 12, 11, 13).forEach { jb.push(it, packet(it)) }
        assertEquals(listOf(10, 11, 12, 13), List(4) { played(tick()) })
        assertEquals(0L, jb.stats().late)
    }

    @Test
    fun `a Wi-Fi burst is absorbed whole`() {
        repeat(15) { jb.push(200 + it, packet(it)) } // 300 ms arriving at once
        assertEquals(List(15) { it }, List(15) { played(tick()) })
        assertEquals(0L, jb.stats().overflows)
        assertEquals(0L, jb.stats().skipped)
    }

    @Test
    fun `a lost packet is rebuilt from the next one's FEC`() {
        listOf(1, 2, 4, 5).forEach { jb.push(it, packet(it)) }
        assertEquals(1, played(tick()))
        assertEquals(2, played(tick()))
        val r = tick()
        assertTrue(r is Next.Recover)
        assertEquals(4, (r as Next.Recover).following[1].toInt())
        assertEquals(4, played(tick()))
        assertEquals(1L, jb.stats().lost)
        assertEquals(1L, jb.stats().recovered)
    }

    @Test
    fun `end of a sentence stops quietly, a stall grows the cushion`() {
        jb.push(1, packet(1)); jb.push(2, packet(2)); jb.push(3, OPUS_SILENCE)
        assertEquals(1, played(tick())); assertEquals(2, played(tick()))
        assertArrayEquals(OPUS_SILENCE, (tick() as Next.Play).packet)
        assertTrue(tick() is Next.Silent)
        assertEquals(0L, jb.stats().underruns)
        assertEquals(3, jb.stats().targetFrames)

        // Mid-speech stall: three concealed frames, then it stops and asks for more cushion.
        jb.push(4, packet(4)); jb.push(5, packet(5)); jb.push(6, packet(6))
        repeat(3) { tick() }
        repeat(3) { assertEquals(Next.Conceal, tick()) }
        assertTrue(tick() is Next.Silent)
        assertEquals(1L, jb.stats().underruns)
        assertEquals(4, jb.stats().targetFrames)
    }

    @Test
    fun `late packets after their slot are dropped and counted`() {
        listOf(1, 2, 3).forEach { jb.push(it, packet(it)) }
        repeat(3) { tick() }
        jb.push(2, packet(2))
        assertEquals(1L, jb.stats().late)
    }

    @Test
    fun `sequence numbers wrap around 65535`() {
        listOf(65534, 65535, 0, 1).forEach { jb.push(it, packet(it and 0x7F)) }
        assertEquals(listOf(65534 and 0x7F, 65535 and 0x7F, 0, 1), List(4) { played(tick()) })
    }

    @Test
    fun `a single short word plays even below the cushion`() {
        jb.push(1, packet(1))
        assertTrue(tick() is Next.Silent)
        assertTrue(tick() is Next.Silent)
        assertTrue(tick() is Next.Silent)
        assertEquals(1, played(tick())) // waited the 60 ms the cushion would have taken
    }

    @Test
    fun `latency stays bounded when far behind`() {
        repeat(40) { jb.push(it, packet(it)) }
        val first = played(tick())
        assertTrue("jumped ahead to stay near the target, started at $first", first >= 40 - 3 - JitterBuffer.MAX_EXCESS)
        assertTrue(jb.stats().skipped > 0)
    }
}

class JitterBufferBurstTest {
    @Test
    fun `power-save bursts with shuffling and loss play without skips`() {
        var clock = 0L
        val jb = JitterBuffer(now = { clock })
        val random = java.util.Random(7)
        var seq = 0
        var plays = 0
        var silentMidStream = 0
        // 30 bursts of 5 packets, one burst every 100 ms; playout pulls every 20 ms.
        repeat(30) {
            val burst = (seq until seq + 5).shuffled(random)
            seq += 5
            for (n in burst) if (random.nextDouble() >= 0.02) jb.push(n, byteArrayOf(0x78, n.toByte()))
            repeat(5) {
                val n = jb.pull()
                if (n is JitterBuffer.Next.Play) plays++
                if (n is JitterBuffer.Next.Silent && plays > 0) silentMidStream++
                clock += 20
            }
        }
        val s = jb.stats()
        println("burst test: plays=$plays silentMid=$silentMidStream stats=$s")
        assertEquals("nothing skipped", 0L, s.skipped)
        assertEquals("nothing dropped as late", 0L, s.late)
    }
}
