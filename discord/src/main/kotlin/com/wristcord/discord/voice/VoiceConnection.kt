package com.wristcord.discord.voice

import com.wristcord.discord.Discord
import com.wristcord.discord.model.DiscordJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What a VOICE_SERVER_UPDATE + our own VOICE_STATE_UPDATE add up to. */
data class VoiceServerInfo(
    /** Guild id, or the channel id for a DM call. */
    val serverId: Long,
    val channelId: Long,
    val userId: Long,
    /** The main gateway's session id. */
    val sessionId: String,
    val token: String,
    /** Host[:port] without scheme, from VOICE_SERVER_UPDATE. */
    val endpoint: String,
)

sealed interface VoiceLinkState {
    data object Connecting : VoiceLinkState
    data object Handshaking : VoiceLinkState
    /**
     * [securing]: the call uses DAVE but this client's MLS group is not ready yet (the first
     * second or so). Speech is held back until it is, so it never leaves without E2EE.
     */
    data class Connected(val e2ee: Boolean, val privacyCode: String, val securing: Boolean = false) : VoiceLinkState
    data class Reconnecting(val attempt: Int) : VoiceLinkState
    data class Failed(val code: Int, val reason: String) : VoiceLinkState
    data object Closed : VoiceLinkState
}

/** What one voice connection has done so far, for the call's diagnostics. */
data class VoiceStats(
    val sent: Long = 0,
    /** Speech dropped while DAVE was not ready yet: it never leaves without E2EE. */
    val heldBack: Long = 0,
    val encryptFailed: Long = 0,
    val received: Long = 0,
    /** Audio packets whose transport encryption did not open. */
    val transportFailed: Long = 0,
    /** Audio from an SSRC that could not be matched to anyone in the call. */
    val unknownSender: Long = 0,
    val daveFailed: Long = 0,
    val delivered: Long = 0,
    val protocolVersion: Int = 0,
    val daveReady: Boolean = false,
    val welcomesRejected: Int = 0,
    val lastDaveError: String = "",
)

/**
 * One connection to a Discord voice server: the v8 voice gateway, the UDP media socket and
 * the DAVE session. Audio goes in as Opus frames through [sendOpus] and comes out through
 * [Listener.onOpus]; everything between is here.
 */
class VoiceConnection(
    private val info: VoiceServerInfo,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Creates the native DAVE session; null if DAVE is unavailable (calls will be refused). */
    private val daveFactory: ((protocolVersion: Int, userId: Long, channelId: Long) -> DaveCrypto?)?,
    private val maxDaveVersion: Int,
    /** XChaCha20-Poly1305 transport, the mode every server offers; Java has no implementation. */
    private val xchachaFactory: ((ByteArray) -> TransportCipher)? = null,
    /** Binds the UDP socket to a specific network (Wi-Fi/LTE rather than a Bluetooth proxy). */
    private val bindSocket: (DatagramSocket) -> Unit = {},
    /**
     * Who Discord's main gateway shows in the call. An MLS welcome or proposal may only contain
     * these users or ones the voice server announced (op 11, op 5). The voice server does not
     * reliably announce people already in the call before it sends the watch its welcome, and a
     * welcome naming someone unknown is refused.
     */
    private val recognizedUsers: () -> Collection<Long> = { emptyList() },
    private val listener: Listener,
) {
    interface Listener {
        fun onOpus(userId: Long?, ssrc: Int, sequence: Int, opus: ByteArray)
        fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean) {}
        fun onClientDisconnect(userId: Long) {}
        fun log(message: String) {}
    }

    private val _state = MutableStateFlow<VoiceLinkState>(VoiceLinkState.Connecting)
    val state: StateFlow<VoiceLinkState> = _state

    @Volatile private var ws: WebSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var sender: RtpSender? = null
    @Volatile private var cipher: TransportCipher? = null
    @Volatile private var dave: DaveCrypto? = null
    @Volatile private var protocolVersion = 0
    @Volatile private var ssrc = 0
    @Volatile private var seqAck = -1
    @Volatile private var speaking = false
    @Volatile private var closed = false
    @Volatile private var lastTransitionId = 0
    @Volatile private var decryptFailures = 0
    @Volatile private var welcomesRejected = 0
    @Volatile private var lastDaveError = ""
    private val sent = AtomicLong()
    private val heldBack = AtomicLong()
    private val encryptFailed = AtomicLong()
    private val received = AtomicLong()
    private val transportFailed = AtomicLong()
    private val unknownSender = AtomicLong()
    private val daveFailed = AtomicLong()
    private val delivered = AtomicLong()

    private val generation = AtomicInteger()
    private var heartbeat: Job? = null
    private var keepalive: Job? = null
    private var resumeAttempts = 0

    /** Users known to be in the media session (op 11 / op 13), for validating MLS adds. */
    private val connected: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val ssrcUsers = ConcurrentHashMap<Int, Long>()
    private val pendingTransitions = ConcurrentHashMap<Int, Int>()
    private val earlyBinary = ArrayList<BinaryVoiceMessage>()

    fun connect() {
        openSocket(resume = false)
    }

    private fun openSocket(resume: Boolean) {
        val gen = generation.incrementAndGet()
        val base = info.endpoint.trimEnd('/').let { if (it.startsWith("ws://") || it.startsWith("wss://")) it else "wss://$it" }
        val url = "$base/?v=8"
        val request = Request.Builder().url(url).header("User-Agent", Discord.USER_AGENT).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get()) return
                if (resume) {
                    sendJson(VoiceOp.RESUME, buildJsonObject {
                        put("server_id", info.serverId.toString())
                        put("session_id", info.sessionId)
                        put("token", info.token)
                        put("seq_ack", seqAck)
                    })
                } else {
                    sendJson(VoiceOp.IDENTIFY, buildJsonObject {
                        put("server_id", info.serverId.toString())
                        put("user_id", info.userId.toString())
                        put("session_id", info.sessionId)
                        put("token", info.token)
                        put("max_dave_protocol_version", if (daveFactory != null) maxDaveVersion else 0)
                    })
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation.get()) return
                runCatching { onJson(DiscordJson.parseToJsonElement(text).jsonObject) }
                    .onFailure { listener.log("voice: bad message: ${it.message}") }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != generation.get()) return
                val msg = BinaryVoiceMessage.parse(bytes.toByteArray()) ?: return
                seqAck = msg.sequence
                runCatching { onBinary(msg) }.onFailure { listener.log("voice: binary op ${msg.opcode} failed: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                onClosed(gen, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(gen, 1006, t.message ?: "network error")
            }
        })
    }

    private fun onClosed(gen: Int, code: Int, reason: String) {
        if (gen != generation.get() || closed) return
        heartbeat?.cancel()
        listener.log("voice: socket closed $code $reason")
        if (VoiceOp.canResume(code) && sender != null && resumeAttempts < MAX_RESUMES) {
            resumeAttempts++
            _state.value = VoiceLinkState.Reconnecting(resumeAttempts)
            scope.launch {
                delay(500L * resumeAttempts)
                if (!closed) openSocket(resume = true)
            }
        } else {
            fail(code, VoiceOp.describeClose(code))
        }
    }

    private fun onJson(o: JsonObject) {
        (o["seq"] as? JsonPrimitive)?.intOrNull?.let { seqAck = it }
        val op = (o["op"] as? JsonPrimitive)?.intOrNull ?: return
        val d = o["d"]
        when (op) {
            VoiceOp.HELLO -> startHeartbeat(d!!.jsonObject["heartbeat_interval"]!!.jsonPrimitive.longOrNull ?: 13_750L)
            VoiceOp.READY -> onReady(d!!.jsonObject)
            VoiceOp.SESSION_DESCRIPTION -> onSessionDescription(d!!.jsonObject)
            VoiceOp.RESUMED -> {
                resumeAttempts = 0
                speaking = false
                _state.value = connectedState()
            }
            VoiceOp.HEARTBEAT_ACK -> Unit
            VoiceOp.SPEAKING -> {
                val dd = d!!.jsonObject
                val user = dd["user_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val s = dd["ssrc"]?.jsonPrimitive?.intOrNull ?: return
                ssrcUsers[s] = user
                connected += user
                listener.onSpeaking(user, s, (dd["speaking"]?.jsonPrimitive?.intOrNull ?: 0) != 0)
            }
            VoiceOp.CLIENTS_CONNECT -> d!!.jsonObject["user_ids"]?.jsonArray?.forEach { e ->
                e.jsonPrimitive.content.toLongOrNull()?.let { connected += it }
            }
            VoiceOp.CLIENT_DISCONNECT -> d!!.jsonObject["user_id"]?.jsonPrimitive?.content?.toLongOrNull()?.let { u ->
                connected -= u
                ssrcUsers.entries.removeIf { it.value == u }
                listener.onClientDisconnect(u)
            }
            VoiceOp.DAVE_PREPARE_TRANSITION -> {
                val dd = d!!.jsonObject
                val tid = dd["transition_id"]!!.jsonPrimitive.int
                val pv = dd["protocol_version"]!!.jsonPrimitive.int
                val session = dave ?: return
                pendingTransitions[tid] = pv
                session.prepareTransition(tid, pv)
                if (pv == 0) session.setPassthroughMode(true, DOWNGRADE_EXPIRY_S)
                if (tid == 0) {
                    pendingTransitions.remove(tid)
                    protocolVersion = pv
                    _state.value = connectedState()
                } else {
                    sendJson(VoiceOp.DAVE_TRANSITION_READY, buildJsonObject { put("transition_id", tid) })
                }
            }
            VoiceOp.DAVE_EXECUTE_TRANSITION -> {
                val tid = d!!.jsonObject["transition_id"]!!.jsonPrimitive.int
                val session = dave ?: return
                val pv = pendingTransitions.remove(tid)
                if (session.executeTransition(tid) == 1 && pv != null) {
                    if (protocolVersion == 0 && pv > 0) session.setPassthroughMode(true, TRANSITION_EXPIRY_S)
                    protocolVersion = pv
                    lastTransitionId = tid
                    _state.value = connectedState()
                }
            }
            VoiceOp.DAVE_PREPARE_EPOCH -> {
                val dd = d!!.jsonObject
                if (dd["epoch"]?.jsonPrimitive?.intOrNull == 1) {
                    protocolVersion = dd["protocol_version"]!!.jsonPrimitive.int
                    reinitDave()
                }
            }
        }
    }

    private fun onReady(d: JsonObject) {
        ssrc = d["ssrc"]!!.jsonPrimitive.int
        val ip = d["ip"]!!.jsonPrimitive.content
        val port = d["port"]!!.jsonPrimitive.int
        val modes = (d["modes"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        _state.value = VoiceLinkState.Handshaking
        val mode = when {
            AesGcmTransport.MODE_AES_GCM in modes -> AesGcmTransport.MODE_AES_GCM
            AesGcmTransport.MODE_XCHACHA in modes && xchachaFactory != null -> AesGcmTransport.MODE_XCHACHA
            else -> {
                fail(4016, "No shared encryption mode (server offers ${modes.joinToString()})")
                return
            }
        }
        val gen = generation.get()
        Thread({ udpHandshake(gen, ip, port, mode) }, "wristcord-voice-udp").apply { isDaemon = true }.start()
    }

    private fun udpHandshake(gen: Int, ip: String, port: Int, mode: String) {
        try {
            val socket = DatagramSocket()
            bindSocket(socket)
            socket.connect(InetSocketAddress(ip, port))
            socket.soTimeout = 1_000
            udp = socket
            var external: Pair<String, Int>? = null
            val buf = ByteArray(2048)
            repeat(5) {
                if (external != null) return@repeat
                socket.send(DatagramPacket(IpDiscovery.request(ssrc), IpDiscovery.SIZE))
                val deadline = System.currentTimeMillis() + 1_000
                while (external == null && System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    if (p.length == IpDiscovery.SIZE) external = IpDiscovery.parse(buf.copyOf(p.length))
                }
            }
            val (address, extPort) = external ?: throw IllegalStateException("IP discovery got no answer")
            if (gen != generation.get() || closed) return
            sendJson(VoiceOp.SELECT_PROTOCOL, buildJsonObject {
                put("protocol", "udp")
                putJsonObject("data") {
                    put("address", address)
                    put("port", extPort)
                    put("mode", mode)
                }
            })
            socket.soTimeout = 0
            startKeepalive()
            receiveLoop(socket)
        } catch (t: Throwable) {
            if (!closed) fail(4000, "Media connection failed: ${t.message}")
        }
    }

    private fun onSessionDescription(d: JsonObject) {
        val mode = d["mode"]!!.jsonPrimitive.content
        val key = d["secret_key"]!!.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
        cipher = when (mode) {
            AesGcmTransport.MODE_AES_GCM -> AesGcmTransport(key)
            AesGcmTransport.MODE_XCHACHA -> xchachaFactory?.invoke(key)
            else -> null
        } ?: run {
            fail(4016, "Unsupported encryption mode $mode")
            return
        }
        sender = RtpSender(ssrc, cipher!!)
        protocolVersion = d["dave_protocol_version"]?.jsonPrimitive?.intOrNull ?: 0
        resumeAttempts = 0
        if (protocolVersion > 0) reinitDave()
        _state.value = connectedState()
        // Replay DAVE messages that raced the session description.
        val early = synchronized(earlyBinary) { earlyBinary.toList().also { earlyBinary.clear() } }
        early.forEach { runCatching { onBinary(it) } }
    }

    private fun reinitDave() {
        val factory = daveFactory ?: return
        val session = dave?.also { it.reinit(protocolVersion) } ?: factory(protocolVersion, info.userId, info.channelId)
        if (session == null) {
            fail(4017, "End-to-end encryption is unavailable on this watch")
            return
        }
        dave = session
        decryptFailures = 0
        session.createKeyPackage()?.let { sendBinary(VoiceOp.DAVE_MLS_KEY_PACKAGE, it) }
            ?: listener.log("voice: key package failed: ${session.lastError}")
    }

    private fun onBinary(msg: BinaryVoiceMessage) {
        val session = dave
        if (session == null || sender == null) {
            synchronized(earlyBinary) { earlyBinary += msg }
            return
        }
        when (msg.opcode) {
            VoiceOp.DAVE_MLS_EXTERNAL_SENDER ->
                if (!session.setExternalSender(msg.payload)) listener.log("voice: external sender rejected: ${session.lastError}")
            VoiceOp.DAVE_MLS_PROPOSALS -> {
                if (msg.payload.isEmpty()) return
                val opType = msg.payload[0].toInt() and 0xFF
                val result = session.processProposals(opType, msg.payload.copyOfRange(1, msg.payload.size), knownUsers())
                when {
                    result == null -> listener.log("voice: proposals failed: ${session.lastError}")
                    result.isNotEmpty() -> sendBinary(VoiceOp.DAVE_MLS_COMMIT_WELCOME, result)
                }
            }
            VoiceOp.DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION -> {
                val tid = u16(msg.payload)
                when (session.processCommit(msg.payload.copyOfRange(2, msg.payload.size))) {
                    1 -> transitionReady(session, tid)
                    0 -> Unit
                    else -> invalid(session, tid)
                }
            }
            VoiceOp.DAVE_MLS_WELCOME -> {
                val tid = u16(msg.payload)
                // A refused welcome cannot be retried (MLS consumed our key package reading it);
                // op 31 makes the server add us again with a fresh one.
                if (session.processWelcome(msg.payload.copyOfRange(2, msg.payload.size), knownUsers())) {
                    listener.log("voice: welcomed to the E2EE group (transition $tid)")
                    transitionReady(session, tid)
                } else {
                    welcomesRejected++
                    invalid(session, tid)
                }
            }
        }
    }

    private fun transitionReady(session: DaveCrypto, tid: Int) {
        session.prepareTransition(tid, protocolVersion)
        if (tid == 0) {
            lastTransitionId = 0
            _state.value = connectedState()
        } else {
            pendingTransitions[tid] = protocolVersion
            sendJson(VoiceOp.DAVE_TRANSITION_READY, buildJsonObject { put("transition_id", tid) })
        }
    }

    private fun invalid(session: DaveCrypto, tid: Int) {
        lastDaveError = session.lastError
        listener.log("voice: invalid commit/welcome for transition $tid: ${session.lastError}")
        sendJson(VoiceOp.DAVE_MLS_INVALID_COMMIT_WELCOME, buildJsonObject { put("transition_id", tid) })
        reinitDave()
    }

    private fun knownUsers(): LongArray =
        (connected + info.userId + runCatching(recognizedUsers).getOrDefault(emptyList())).toLongArray()

    fun stats() = VoiceStats(
        sent = sent.get(),
        heldBack = heldBack.get(),
        encryptFailed = encryptFailed.get(),
        received = received.get(),
        transportFailed = transportFailed.get(),
        unknownSender = unknownSender.get(),
        daveFailed = daveFailed.get(),
        delivered = delivered.get(),
        protocolVersion = protocolVersion,
        daveReady = dave?.isReady == true,
        welcomesRejected = welcomesRejected,
        lastDaveError = lastDaveError,
    )

    private fun connectedState() = VoiceLinkState.Connected(
        e2ee = protocolVersion > 0 && dave?.isReady == true,
        privacyCode = if (protocolVersion > 0) dave?.voicePrivacyCode.orEmpty() else "",
        securing = protocolVersion > 0 && dave?.isReady != true,
    )

    // ---- media ------------------------------------------------------------------------------

    /** Encrypts and sends one 20 ms Opus frame. Safe to call from the capture thread. */
    fun sendOpus(opus: ByteArray) {
        val s = sender ?: return
        val socket = udp ?: return
        if (!speaking) setSpeaking(true)
        val session = dave
        val frame = when {
            // Silence frames always go out as they are (DAVE spec: "silence packets").
            protocolVersion == 0 || opus.contentEquals(OPUS_SILENCE) -> opus
            // A DAVE call whose MLS group is still forming: drop speech rather than send it with
            // transport encryption only, which Discord's servers could read.
            session == null || !session.isReady -> {
                heldBack.incrementAndGet()
                return
            }
            else -> session.encryptOpus(opus) ?: run {
                encryptFailed.incrementAndGet()
                return
            }
        }
        val packet = s.packet(frame)
        runCatching { socket.send(DatagramPacket(packet, packet.size)) }.onSuccess { sent.incrementAndGet() }
    }

    /** Stop transmitting: five silence frames (so the far side does not interpolate), then op 5 off. */
    fun stopTransmitting() {
        if (!speaking) return
        repeat(5) { sendOpus(OPUS_SILENCE) }
        setSpeaking(false)
    }

    private fun setSpeaking(on: Boolean) {
        if (speaking == on) return
        speaking = on
        sendJson(VoiceOp.SPEAKING, buildJsonObject {
            put("speaking", if (on) 1 else 0)
            put("delay", 0)
            put("ssrc", ssrc)
        })
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!closed && !socket.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (t: Throwable) {
                if (!closed) listener.log("voice: udp receive stopped: ${t.message}")
                return
            }
            received.incrementAndGet()
            val c = cipher ?: continue
            if (p.length < 12) continue
            val rtp = RtpReceiver.open(buf.copyOf(p.length), c) ?: run {
                if (buf[1].toInt() and 0x7F == RtpSender.PAYLOAD_TYPE) transportFailed.incrementAndGet()
                null
            } ?: continue
            val (user, opus) = daveDecrypt(rtp.ssrc, rtp.payload) ?: continue
            delivered.incrementAndGet()
            // A bad frame or a failing decoder must not take the whole receive path down.
            runCatching { listener.onOpus(user, rtp.ssrc, rtp.sequence, opus) }
                .onFailure { if (delivered.get() < 5 || delivered.get() % 500 == 0L) listener.log("voice: playback failed: $it") }
        }
    }

    /**
     * discord.js's rule: pass through silence and anything we cannot decrypt yet; drop failures.
     * Returns who sent the frame (when known) and the Opus inside it.
     */
    private fun daveDecrypt(ssrc: Int, frame: ByteArray): Pair<Long?, ByteArray>? {
        var user = ssrcUsers[ssrc]
        if (frame.contentEquals(OPUS_SILENCE)) return user to frame
        val session = dave
        if (protocolVersion == 0 && (session == null || user == null || !session.canPassthrough(user))) return user to frame
        if (session == null || !session.isReady) return null
        if (user == null) {
            // No op 5 for this SSRC yet: each member has their own key, so the one whose key
            // opens the frame is the sender. (Decrypting twice would trip replay protection.)
            return identify(session, ssrc, frame) ?: run {
                unknownSender.incrementAndGet()
                null
            }
        }
        val out = session.decrypt(user, frame)
        if (out != null) {
            decryptFailures = 0
            return user to out
        }
        daveFailed.incrementAndGet()
        if (pendingTransitions.isEmpty() && ++decryptFailures > DECRYPT_FAILURE_TOLERANCE) {
            decryptFailures = 0
            invalid(session, lastTransitionId)
        }
        return null
    }

    private fun identify(session: DaveCrypto, ssrc: Int, frame: ByteArray): Pair<Long, ByteArray>? {
        val candidates = (connected + runCatching(recognizedUsers).getOrDefault(emptyList())) - info.userId
        for (candidate in candidates.take(MAX_SENDER_GUESSES)) {
            val out = session.decrypt(candidate, frame) ?: continue
            ssrcUsers[ssrc] = candidate
            listener.log("voice: ssrc $ssrc is user $candidate (matched by key)")
            return candidate to out
        }
        return null
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private fun startHeartbeat(interval: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive && !closed) {
                sendJson(VoiceOp.HEARTBEAT, buildJsonObject {
                    put("t", System.currentTimeMillis())
                    put("seq_ack", seqAck)
                })
                delay(interval)
            }
        }
    }

    private fun startKeepalive() {
        keepalive?.cancel()
        keepalive = scope.launch {
            var counter = 0L
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive && !closed) {
                buf.clear()
                buf.putInt(counter.toInt())
                buf.putInt(0)
                val bytes = buf.array().copyOf()
                runCatching { udp?.send(DatagramPacket(bytes, bytes.size)) }
                counter = (counter + 1) and 0xFFFFFFFFL
                delay(5_000)
            }
        }
    }

    private fun sendJson(op: Int, d: JsonObject) {
        ws?.send(buildJsonObject {
            put("op", op)
            put("d", d)
        }.toString())
    }

    private fun sendBinary(op: Int, payload: ByteArray) {
        ws?.send(BinaryVoiceMessage.frame(op, payload).toByteString())
    }

    private fun fail(code: Int, reason: String) {
        listener.log("voice: failed $code $reason")
        _state.value = VoiceLinkState.Failed(code, reason)
        teardown()
    }

    fun close() {
        if (closed) return
        runCatching { stopTransmitting() }
        teardown()
        _state.value = VoiceLinkState.Closed
    }

    private fun teardown() {
        closed = true
        generation.incrementAndGet()
        heartbeat?.cancel()
        keepalive?.cancel()
        runCatching { ws?.close(1000, null) }
        runCatching { udp?.close() }
        runCatching { dave?.close() }
        ws = null
        udp = null
        dave = null
    }

    private fun u16(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    companion object {
        const val MAX_RESUMES = 5
        const val TRANSITION_EXPIRY_S = 10
        const val DOWNGRADE_EXPIRY_S = 24
        /** discord.js's default: this many undecryptable frames in a row means the keys diverged. */
        const val DECRYPT_FAILURE_TOLERANCE = 36
        /** Calls on a watch are small; never try more keys than this for one unknown SSRC. */
        const val MAX_SENDER_GUESSES = 25
    }
}
