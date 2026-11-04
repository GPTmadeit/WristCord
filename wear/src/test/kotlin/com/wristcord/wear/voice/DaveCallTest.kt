package com.wristcord.wear.voice

import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.voice.AesGcmTransport
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real DAVE join, end to end, with davey on both sides:
 * - the watch is the real [VoiceConnection] with [NativeDave];
 * - the other person already in the call is a second [NativeDave] session;
 * - the fake voice server plays Discord, the MLS external sender included ([TestExternalSender]).
 *
 * The flow follows daveprotocol.com:
 * 1. session description (DAVE v1), then the watch's key package (26) and the external sender (25);
 * 2. proposals to the member already in the call, whose commit goes back to it (29) and whose
 *    welcome goes to the watch (30);
 * 3. transition ready (23), then execute (22).
 *
 * Then audio must decrypt in both directions. This is the test the fake-DAVE
 * `VoiceConnectionTest` cannot be: it catches any mismatch between our message handling and
 * davey's state machine.
 */
class DaveCallTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val udp = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val fromWatch = LinkedBlockingQueue<ByteArray>()
    @Volatile private var watchAddr: Pair<InetAddress, Int>? = null
    private val server = MockWebServer()
    private val serverSeq = AtomicInteger(1)
    private val sentByWatch = LinkedBlockingQueue<String>()
    private lateinit var serverSocket: WebSocket
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val external = TestExternalSender(CHANNEL)
    private val other = NativeDave.create(1, OTHER, CHANNEL)!!
    private val log = LinkedBlockingQueue<String>()

    @Before
    fun setUp() {
        assertTrue("native DAVE library did not load on the host", NativeDave.available)
        assertTrue(other.setExternalSender(external.externalSenderPackage))
        Thread {
            val buf = ByteArray(2048)
            while (!udp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { udp.receive(p) } catch (_: Throwable) { break }
                val data = buf.copyOf(p.length)
                watchAddr = p.address to p.port
                if (p.length == 74 && data[1].toInt() == 1) {
                    val resp = ByteBuffer.allocate(74).putShort(2).putShort(70).putInt(42).put("127.0.0.1".toByteArray()).array()
                    resp[72] = (p.port shr 8).toByte(); resp[73] = (p.port and 0xFF).toByte()
                    udp.send(DatagramPacket(resp, 74, p.address, p.port))
                } else if (p.length > 12) fromWatch += data
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { serverSocket.close(1000, null) }
        runCatching { server.close() }
        udp.close()
        other.close()
        external.close()
    }

    private fun sendBinary(op: Int, payload: ByteArray) {
        val seq = serverSeq.incrementAndGet()
        serverSocket.send((byteArrayOf((seq shr 8).toByte(), seq.toByte(), op.toByte()) + payload).toByteString())
    }

    private fun sendJson(op: Int, d: String) {
        serverSocket.send("""{"op":$op,"seq":${serverSeq.incrementAndGet()},"d":$d}""")
    }

    /** The server side of the call: Discord's sequence, with [other] as the member already there. */
    private fun startServer(announceUsers: Boolean) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"op":8,"d":{"heartbeat_interval":5000}}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                sentByWatch += text
                val o = DiscordJson.parseToJsonElement(text).jsonObject
                when (o["op"]!!.jsonPrimitive.int) {
                    VoiceOp.IDENTIFY -> sendJson(VoiceOp.READY,
                        """{"ssrc":42,"ip":"127.0.0.1","port":${udp.localPort},"modes":["aead_aes256_gcm_rtpsize"]}""")
                    VoiceOp.SELECT_PROTOCOL -> {
                        sendJson(VoiceOp.SESSION_DESCRIPTION,
                            """{"mode":"aead_aes256_gcm_rtpsize","secret_key":[${key.joinToString(",") { (it.toInt() and 0xFF).toString() }}],"dave_protocol_version":1}""")
                        if (announceUsers) sendJson(VoiceOp.CLIENTS_CONNECT, """{"user_ids":["$OTHER"]}""")
                        sendBinary(VoiceOp.DAVE_MLS_EXTERNAL_SENDER, external.externalSenderPackage)
                    }
                    VoiceOp.DAVE_TRANSITION_READY -> {
                        val tid = o["d"]!!.jsonObject["transition_id"]!!.jsonPrimitive.int
                        assertEquals(1, other.executeTransition(tid))
                        sendJson(VoiceOp.DAVE_EXECUTE_TRANSITION, """{"transition_id":$tid}""")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b[0].toInt() != VoiceOp.DAVE_MLS_KEY_PACKAGE) return
                // The watch's key package: propose adding it to the other member's group
                // (still its pending group at epoch 0), which commits and welcomes the watch.
                val proposals = external.proposeAdd(0, b.copyOfRange(1, b.size))
                val commitWelcome = other.processProposals(0, proposals, longArrayOf(OTHER, WATCH))
                assertNotNull("other member could not process proposals: ${other.lastError}", commitWelcome)
                val (commit, welcome) = TestExternalSender.splitCommitWelcome(commitWelcome!!)
                assertEquals(1, other.processCommit(commit))
                assertTrue(other.prepareTransition(TRANSITION, 1))
                sendBinary(VoiceOp.DAVE_MLS_WELCOME, byteArrayOf(0, TRANSITION.toByte()) + welcome!!)
            }
        }).build())
        server.start()
    }

    private fun connect(recognized: Collection<Long> = emptyList()): Pair<VoiceConnection, LinkedBlockingQueue<Pair<Long?, ByteArray>>> {
        val heard = LinkedBlockingQueue<Pair<Long?, ByteArray>>()
        val conn = VoiceConnection(
            info = VoiceServerInfo(serverId = 1000, channelId = CHANNEL, userId = WATCH, sessionId = "s-watch", token = "tok",
                endpoint = "ws://${server.hostName}:${server.port}"),
            http = OkHttpClient(),
            scope = scope,
            daveFactory = { pv, uid, cid -> NativeDave.create(pv, uid, cid) },
            maxDaveVersion = NativeDave.maxProtocolVersion,
            xchachaFactory = NativeDave::xchacha,
            recognizedUsers = { recognized },
            listener = object : VoiceConnection.Listener {
                override fun onOpus(userId: Long?, ssrc: Int, sequence: Int, opus: ByteArray) { heard += userId to opus }
                override fun log(message: String) { log += message; println(message) }
            },
        )
        conn.connect()
        return conn to heard
    }

    private fun awaitEncrypted(conn: VoiceConnection) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if ((conn.state.value as? VoiceLinkState.Connected)?.e2ee == true) return
            Thread.sleep(20)
        }
        throw AssertionError("never end-to-end encrypted: ${conn.state.value}; log: ${log.toList()}")
    }

    @Test
    fun `watch joins an existing call and audio decrypts both ways`() {
        startServer(announceUsers = true)
        val (conn, heard) = connect()
        awaitEncrypted(conn)
        val state = conn.state.value as VoiceLinkState.Connected
        assertTrue("no privacy code", state.privacyCode.isNotBlank())
        assertEquals("both ends must be in the same MLS group", other.voicePrivacyCode, state.privacyCode)

        // The other member talks: its frame is DAVE-encrypted by its own session.
        sendJson(VoiceOp.SPEAKING, """{"user_id":"$OTHER","ssrc":77,"speaking":1}""")
        Thread.sleep(200)
        val theirs = byteArrayOf(0x78, 1, 2, 3, 4, 5)
        val frame = other.encryptOpus(theirs)
        assertNotNull("other member cannot encrypt: ${other.lastError}", frame)
        val packet = RtpSender(77, AesGcmTransport(key)).packet(frame!!)
        val (addr, port) = watchAddr!!
        udp.send(DatagramPacket(packet, packet.size, addr, port))
        val (who, opus) = heard.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the watch heard nothing; log: ${log.toList()}")
        assertEquals(OTHER, who)
        assertArrayEquals(theirs, opus)

        // The watch talks: the other member must be able to decrypt it.
        val mine = byteArrayOf(0x78, 9, 8, 7, 6)
        conn.sendOpus(mine)
        val rtp = fromWatch.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the watch sent nothing; log: ${log.toList()}")
        val opened = RtpReceiver.open(rtp, AesGcmTransport(key))!!
        assertEquals(42, opened.ssrc)
        val decrypted = other.decrypt(WATCH, opened.payload)
        assertNotNull("other member could not decrypt the watch: ${other.lastError}", decrypted)
        assertArrayEquals(mine, decrypted)
        conn.close()
    }

    /**
     * What broke the first real call: the voice server sent the welcome before announcing the
     * member already in the call, and the watch refused it. The main gateway's view of the
     * channel must be enough. With no op 5 either, the sender is found by whose key opens it.
     */
    @Test
    fun `welcome before the voice server lists anyone, sender found by key`() {
        startServer(announceUsers = false)
        val (conn, heard) = connect(recognized = listOf(OTHER, WATCH))
        awaitEncrypted(conn)

        val theirs = byteArrayOf(0x78, 4, 4, 4)
        val packet = RtpSender(91, AesGcmTransport(key)).packet(other.encryptOpus(theirs)!!)
        val (addr, port) = watchAddr!!
        udp.send(DatagramPacket(packet, packet.size, addr, port))
        val (who, opus) = heard.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the watch heard nothing; log: ${log.toList()}")
        assertEquals(OTHER, who)
        assertArrayEquals(theirs, opus)
        assertEquals(0, conn.stats().welcomesRejected)
        conn.close()
    }

    /** And without either source, the welcome is refused: the check is still there. */
    @Test
    fun `welcome naming nobody known is refused`() {
        startServer(announceUsers = false)
        val (conn, _) = connect()
        val deadline = System.currentTimeMillis() + 5_000
        while (conn.stats().welcomesRejected == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, conn.stats().welcomesRejected)
        assertTrue(conn.stats().lastDaveError, conn.stats().lastDaveError.contains("unrecognized user"))
        conn.close()
    }

    private companion object {
        const val WATCH = 222_222_222_222_222_222L
        const val OTHER = 333_333_333_333_333_333L
        const val CHANNEL = 444_444_444_444_444_444L
        const val TRANSITION = 1
    }
}
