package com.wristcord.discord

import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.voice.AesGcmTransport
import com.wristcord.discord.voice.BinaryVoiceMessage
import com.wristcord.discord.voice.DaveCrypto
import com.wristcord.discord.voice.IpDiscovery
import com.wristcord.discord.voice.OPUS_SILENCE
import com.wristcord.discord.voice.RtpReceiver
import com.wristcord.discord.voice.RtpSender
import com.wristcord.discord.voice.VoiceConnection
import com.wristcord.discord.voice.VoiceLinkState
import com.wristcord.discord.voice.VoiceOp
import com.wristcord.discord.voice.VoiceServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RtpTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `aes-gcm rtpsize round trip and layout`() {
        val c = AesGcmTransport(key)
        val p = RtpSender(ssrc = 0x01020304, cipher = c).packet(byteArrayOf(1, 2, 3))
        assertEquals(0x80.toByte(), p[0])
        assertEquals("first packet: marker bit + payload type 0x78", 0xF8.toByte(), p[1])
        assertEquals(12 + 3 + 16 + 4, p.size) // header + ciphertext + tag + nonce
        val r = RtpReceiver.open(p, c)!!
        assertEquals(0x01020304, r.ssrc)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.payload)
    }

    @Test
    fun `timestamps follow capture time across pauses, marker starts each talkspurt`() {
        val c = AesGcmTransport(key)
        val s = RtpSender(ssrc = 9, cipher = c)
        fun ts(p: ByteArray) = ByteBuffer.wrap(p, 4, 4).int.toLong() and 0xFFFFFFFFL
        fun seq(p: ByteArray) = ByteBuffer.wrap(p, 2, 2).short.toInt() and 0xFFFF
        val a = s.packet(byteArrayOf(1), frame = 10)
        val b = s.packet(byteArrayOf(2), frame = 11)
        val silence = s.packet(OPUS_SILENCE) // straight after: frame 12
        val after = s.packet(byteArrayOf(3), frame = 110) // two seconds later
        assertEquals(960L, (ts(b) - ts(a)) and 0xFFFFFFFFL)
        assertEquals(960L, (ts(silence) - ts(b)) and 0xFFFFFFFFL)
        assertEquals(98L * 960, (ts(after) - ts(silence)) and 0xFFFFFFFFL)
        assertEquals(0x78.toByte(), b[1]) // no marker mid-sentence
        assertEquals(0xF8.toByte(), after[1]) // marker after the pause
        assertEquals(1, (seq(after) - seq(silence)) and 0xFFFF) // sequence never skips
        // Speech resuming within the silence frames' timestamps still moves forward.
        val early = s.packet(byteArrayOf(4), frame = 105)
        assertEquals(960L, (ts(early) - ts(after)) and 0xFFFFFFFFL)
    }

    @Test
    fun `incoming extension body is decrypted then skipped`() {
        val c = AesGcmTransport(key)
        // X bit set, one 32-bit extension word: header(12) + ext preamble(4) are AAD,
        // the extension body is encrypted together with the Opus payload.
        val header = byteArrayOf(0x90.toByte(), 0x78, 0, 1, 0, 0, 0, 10, 0, 0, 0, 7) + byteArrayOf(0xBE.toByte(), 0xDE.toByte(), 0, 1)
        val body = byteArrayOf(9, 9, 9, 9) + byteArrayOf(42, 43)
        val nonce4 = byteArrayOf(0, 0, 0, 5)
        val sealed = c.seal(nonce4 + ByteArray(8), header, body)
        val r = RtpReceiver.open(header + sealed + nonce4, c)!!
        assertArrayEquals(byteArrayOf(42, 43), r.payload)
        assertEquals(7, r.ssrc)
    }

    @Test
    fun `ip discovery packet`() {
        val req = IpDiscovery.request(99)
        assertEquals(74, req.size)
        assertEquals(1, req[1].toInt())
        assertEquals(70, req[3].toInt())
        val resp = ByteBuffer.allocate(74).putShort(2).putShort(70).putInt(99).put("203.0.113.7".toByteArray()).array()
        resp[72] = (50000 shr 8).toByte(); resp[73] = (50000 and 0xFF).toByte()
        assertEquals("203.0.113.7" to 50000, IpDiscovery.parse(resp))
    }

    @Test
    fun `binary framing`() {
        val m = BinaryVoiceMessage.parse(byteArrayOf(0x01, 0x02, 27, 0, 9))!!
        assertEquals(0x0102, m.sequence)
        assertEquals(27, m.opcode)
        assertArrayEquals(byteArrayOf(0, 9), m.payload)
        assertArrayEquals(byteArrayOf(26, 5), BinaryVoiceMessage.frame(26, byteArrayOf(5)))
    }
}

/** A stand-in for libdave: "encrypts" by prefixing 0xDA and records the MLS calls made. */
private class FakeDave : DaveCrypto {
    val calls = LinkedBlockingQueue<String>()
    /** Like the real session: not ready until a commit or welcome puts it in the MLS group. */
    @Volatile var ready = false
    override fun reinit(protocolVersion: Int) = true.also { calls += "reinit" }
    override fun reset() { calls += "reset" }
    override fun setExternalSender(data: ByteArray) = true.also { calls += "external:${data.size}" }
    override fun createKeyPackage() = byteArrayOf(7, 7, 7).also { calls += "keypackage" }
    override fun processProposals(operationType: Int, data: ByteArray, expectedUserIds: LongArray): ByteArray =
        byteArrayOf(0xC0.toByte()).also { calls += "proposals:$operationType:${expectedUserIds.sorted()}" }
    override fun processWelcome(data: ByteArray, recognizedUserIds: LongArray) = true.also { calls += "welcome"; ready = true }
    override fun processCommit(data: ByteArray) = 1.also { calls += "commit"; ready = true }
    override fun prepareTransition(transitionId: Int, protocolVersion: Int) = true.also { calls += "prepare:$transitionId" }
    override fun executeTransition(transitionId: Int) = 1.also { calls += "execute:$transitionId" }
    override fun encryptOpus(frame: ByteArray) = byteArrayOf(0xDA.toByte()) + frame
    override fun decrypt(userId: Long, frame: ByteArray) =
        if (frame.firstOrNull() == 0xDA.toByte()) frame.copyOfRange(1, frame.size) else null
    override val isReady: Boolean get() = ready
    override fun canPassthrough(userId: Long) = false
    override fun setPassthroughMode(enabled: Boolean, expirySeconds: Int) {}
    override val voicePrivacyCode = "12345 67890"
    override val lastError = ""
    override fun close() {}
}

/**
 * The real VoiceConnection against a fake voice server: WebSocket handshake, IP discovery over
 * UDP, protocol selection, session description, DAVE key package and MLS proposals, then
 * encrypted RTP in both directions.
 */
class VoiceConnectionTest {

    @Test
    fun `handshake, dave flow and media round trip`() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val udp = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val fromClient = LinkedBlockingQueue<ByteArray>()
        var clientAddr: Pair<InetAddress, Int>? = null
        val udpThread = Thread {
            val buf = ByteArray(2048)
            while (!udp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (_: Throwable) { break }
                val data = buf.copyOf(p.length)
                clientAddr = p.address to p.port
                if (p.length == 74 && data[1].toInt() == 1) {
                    val resp = ByteBuffer.allocate(74).putShort(2).putShort(70).putInt(42).put("127.0.0.1".toByteArray()).array()
                    resp[72] = (p.port shr 8).toByte(); resp[73] = (p.port and 0xFF).toByte()
                    udp.send(DatagramPacket(resp, 74, p.address, p.port))
                } else if (p.length > 12) fromClient += data
            }
        }.apply { isDaemon = true; start() }

        val serverText = LinkedBlockingQueue<String>()
        val serverBinary = LinkedBlockingQueue<ByteArray>()
        lateinit var serverSocket: WebSocket
        val opened = CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"op":8,"d":{"heartbeat_interval":5000}}""")
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverText += text
                val o = DiscordJson.parseToJsonElement(text).jsonObject
                when (o["op"]!!.jsonPrimitive.int) {
                    VoiceOp.IDENTIFY -> webSocket.send(
                        """{"op":2,"seq":1,"d":{"ssrc":42,"ip":"127.0.0.1","port":${udp.localPort},"modes":["aead_aes256_gcm_rtpsize","aead_xchacha20_poly1305_rtpsize"]}}"""
                    )
                    VoiceOp.SELECT_PROTOCOL -> webSocket.send(
                        """{"op":4,"seq":2,"d":{"mode":"aead_aes256_gcm_rtpsize","secret_key":[${key.joinToString(",") { (it.toInt() and 0xFF).toString() }}],"dave_protocol_version":1}}"""
                    )
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { serverBinary += bytes.toByteArray() }
        }).build())
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dave = FakeDave()
        val heard = LinkedBlockingQueue<Pair<Long?, ByteArray>>()
        val conn = VoiceConnection(
            info = VoiceServerInfo(serverId = 1000, channelId = 1201, userId = 10, sessionId = "s-watch", token = "tok",
                endpoint = "ws://${server.hostName}:${server.port}"),
            http = OkHttpClient(),
            scope = scope,
            daveFactory = { _, _, _ -> dave },
            maxDaveVersion = 1,
            listener = object : VoiceConnection.Listener {
                override fun onOpus(userId: Long?, ssrc: Int, sequence: Int, opus: ByteArray) { heard += userId to opus }
                override fun log(message: String) = println(message)
            },
        )
        conn.connect()

        // IDENTIFY carries the session and our DAVE support.
        val identify = DiscordJson.parseToJsonElement(serverText.poll(10, TimeUnit.SECONDS)!!).jsonObject
        assertEquals(VoiceOp.IDENTIFY, identify["op"]!!.jsonPrimitive.int)
        assertEquals(1, identify["d"]!!.jsonObject["max_dave_protocol_version"]!!.jsonPrimitive.int)
        assertEquals("s-watch", identify["d"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)

        // After the session description: a key package (binary op 26, no sequence number).
        val kp = serverBinary.poll(10, TimeUnit.SECONDS)
        assertNotNull("no key package", kp)
        assertArrayEquals(byteArrayOf(26, 7, 7, 7), kp)
        val deadline = System.currentTimeMillis() + 5_000
        while (conn.state.value !is VoiceLinkState.Connected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("state ${conn.state.value}", conn.state.value is VoiceLinkState.Connected)

        // Until the MLS group is ready, speech is held back - never sent with transport
        // encryption only - while silence frames still go out as they are.
        assertTrue((conn.state.value as VoiceLinkState.Connected).securing)
        conn.sendOpus(byteArrayOf(1, 1, 1))
        conn.stopTransmitting()
        repeat(5) {
            val p = fromClient.poll(2, TimeUnit.SECONDS)
            assertNotNull("silence frame ${it + 1} missing", p)
            assertArrayEquals(OPUS_SILENCE, RtpReceiver.open(p!!, AesGcmTransport(key))!!.payload)
        }
        assertNull("speech went out before DAVE was ready", fromClient.poll(300, TimeUnit.MILLISECONDS))

        // MLS: external sender, a user joins, proposals -> we answer with commit/welcome (op 28).
        opened.await(5, TimeUnit.SECONDS)
        serverSocket.send("""{"op":11,"seq":3,"d":{"user_ids":["20"]}}""")
        serverSocket.send(byteArrayOf(0, 4, 25, 1, 2, 3).toByteString())
        serverSocket.send(byteArrayOf(0, 5, 27, 0, 9, 9).toByteString())
        assertEquals(byteArrayOf(28, 0xC0.toByte()).toList(), serverBinary.poll(5, TimeUnit.SECONDS)!!.toList())
        assertTrue(dave.calls.contains("proposals:0:[10, 20]"))

        // Commit for transition 3 -> we report ready (op 23); execute switches over.
        serverSocket.send(byteArrayOf(0, 6, 29, 0, 3, 1, 1).toByteString())
        var ready: String? = null
        while (ready == null) {
            val t = serverText.poll(5, TimeUnit.SECONDS) ?: break
            if (t.contains("\"op\":23")) ready = t
        }
        assertNotNull("no transition ready", ready)
        assertTrue(ready!!.contains("\"transition_id\":3"))

        // Outgoing audio: RTP over UDP, transport-encrypted around the DAVE frame.
        conn.sendOpus(byteArrayOf(5, 6, 7))
        val rtp = fromClient.poll(5, TimeUnit.SECONDS)!!
        val opened2 = RtpReceiver.open(rtp, AesGcmTransport(key))!!
        assertEquals(42, opened2.ssrc)
        assertArrayEquals(byteArrayOf(0xDA.toByte(), 5, 6, 7), opened2.payload)
        // Speaking must precede audio.
        // The WebSocket message can land after the UDP packet; wait for it rather than peek.
        assertTrue(generateSequence { serverText.poll(2, TimeUnit.SECONDS) }.any { it.contains("\"op\":5") && it.contains("\"speaking\":1") })

        // Incoming audio from user 20 on ssrc 77.
        serverSocket.send("""{"op":5,"seq":7,"d":{"user_id":"20","ssrc":77,"speaking":1}}""")
        Thread.sleep(200)
        val packet = RtpSender(77, AesGcmTransport(key)).packet(byteArrayOf(0xDA.toByte(), 1, 2))
        val (addr, port) = clientAddr!!
        udp.send(DatagramPacket(packet, packet.size, addr, port))
        val (who, opus) = heard.poll(5, TimeUnit.SECONDS)!!
        assertEquals(20L, who)
        assertArrayEquals(byteArrayOf(1, 2), opus)

        conn.close()
        scope.cancel()
        // Server-side sockets from MockWebServer cannot be cancelled; closing is enough.
        runCatching { serverSocket.close(1000, null) }
        runCatching { server.close() }
        udp.close()
        udpThread.join(1000)
    }
}
