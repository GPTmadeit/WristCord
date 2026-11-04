package com.wristcord.discord.gateway

import com.wristcord.discord.ClientIdentity
import com.wristcord.discord.Discord
import com.wristcord.discord.model.DiscordJson
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/** Gateway opcodes, from GatewaySocketOpcode.tsx. */
object Op {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val VOICE_STATE_UPDATE = 4
    const val RESUME = 6
    const val RECONNECT = 7
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
    const val GUILD_SUBSCRIPTIONS_BULK = 37
}

sealed interface GatewayState {
    data object Idle : GatewayState
    data object Connecting : GatewayState
    data object Identifying : GatewayState
    data class Connected(val resumed: Boolean) : GatewayState
    data class Waiting(val attempt: Int, val retryAtMs: Long, val reason: String) : GatewayState
    /** Will not reconnect on its own - the token is bad or the account is gone. */
    data class Fatal(val code: Int, val reason: String) : GatewayState
}

/**
 * One connection to Discord's gateway: HELLO, heartbeat, IDENTIFY or RESUME, dispatch, and
 * reconnecting with backoff. READY is decoded straight from the inflated bytes into
 * [Ready] instead of through a JSON tree - it is by far the largest thing a watch ever parses.
 */
class Gateway(
    private val http: OkHttpClient,
    private val identity: ClientIdentity,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(ready: Ready)
        fun onReadySupplemental(supplemental: ReadySupplemental)
        fun onDispatch(type: String, data: JsonElement)
        fun onResumed() {}
        /** 4004: the token was rejected. The caller should sign out. */
        fun onAuthenticationFailed()
    }

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val state: StateFlow<GatewayState> = _state

    @Volatile private var token: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeUrl: String? = null
    @Volatile private var seq: Long? = null
    @Volatile private var heartbeatAcked = true
    @Volatile private var wanted = false
    @Volatile var presenceStatus: String = "unknown"
    @Volatile var afk: Boolean = true

    private val generation = AtomicInteger()
    private var heartbeatJob: Job? = null
    private var retryJob: Job? = null
    private var attempts = 0

    val isResumable: Boolean get() = sessionId != null && seq != null

    fun connect(token: String) {
        this.token = token
        wanted = true
        if (socket != null) return
        retryJob?.cancel()
        open()
    }

    /**
     * [resumable] closes with 4000 so Discord keeps the session for a few minutes and the next
     * [connect] can RESUME instead of downloading READY again. Signing out passes false.
     */
    fun disconnect(resumable: Boolean) {
        wanted = false
        retryJob?.cancel()
        heartbeatJob?.cancel()
        generation.incrementAndGet()
        socket?.close(if (resumable) 4000 else 1000, if (resumable) "backgrounded" else "signed out")
        socket = null
        if (!resumable) {
            sessionId = null
            seq = null
            resumeUrl = null
        }
        _state.value = GatewayState.Idle
    }

    fun send(op: Int, d: JsonElement): Boolean {
        val payload = buildJsonObject {
            put("op", op)
            put("d", d)
        }
        return socket?.send(payload.toString()) ?: false
    }

    fun updatePresence(status: String = presenceStatus, afk: Boolean = this.afk) {
        presenceStatus = status
        this.afk = afk
        send(Op.PRESENCE_UPDATE, presence())
    }

    /** Op 4. `channelId = null` leaves voice in [guildId] (or the DM call when guildId is null). */
    fun voiceStateUpdate(guildId: Long?, channelId: Long?, selfMute: Boolean, selfDeaf: Boolean) {
        send(Op.VOICE_STATE_UPDATE, buildJsonObject {
            put("guild_id", guildId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("channel_id", channelId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("self_mute", selfMute)
            put("self_deaf", selfDeaf)
            put("self_video", false)
        })
    }

    /**
     * Op 37. Discord only streams every message of a large server to clients that say they are
     * looking at it; this is what keeps an open channel live.
     */
    fun subscribe(guildId: Long, channelId: Long?) {
        send(Op.GUILD_SUBSCRIPTIONS_BULK, buildJsonObject {
            putJsonObject("subscriptions") {
                putJsonObject(guildId.toString()) {
                    put("typing", true)
                    put("threads", false)
                    put("activities", false)
                    if (channelId != null) {
                        putJsonObject("channels") {
                            put(channelId.toString(), JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(99))))))
                        }
                    }
                }
            }
        })
    }

    private fun presence() = buildJsonObject {
        put("status", presenceStatus)
        put("since", 0)
        putJsonArray("activities") {}
        // afk = true tells Discord to keep routing push notifications to the phone while this
        // session is connected - the watch must never steal the phone's notifications.
        put("afk", afk)
    }

    private fun open() {
        val gen = generation.incrementAndGet()
        _state.value = GatewayState.Connecting
        val base = resumeUrl?.takeIf { isResumable }
        val url = if (base != null) "$base/?encoding=json&v=${Discord.API_VERSION}&compress=zlib-stream" else Discord.GATEWAY_URL
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Discord.USER_AGENT)
            .build()
        val zlib = ZlibStream()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != generation.get()) return
                val payload = try {
                    zlib.feed(bytes.toByteArray())
                } catch (t: Throwable) {
                    fail(gen, 4000, "decompression failed: ${t.message}")
                    return
                } ?: return
                handle(gen, payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation.get()) return
                handle(gen, text.toByteArray(Charsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                zlib.close()
                closed(gen, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                zlib.close()
                closed(gen, response?.code ?: -1, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun handle(gen: Int, payload: ByteArray) {
        val head = String(payload, 0, min(payload.size, HEAD_BYTES), Charsets.UTF_8)
        try {
            when {
                head.contains("\"t\":\"READY\"") -> {
                    val frame = DiscordJson.decodeFromStream(ReadyFrame.serializer(), ByteArrayInputStream(payload))
                    frame.s?.let { seq = it }
                    sessionId = frame.d.sessionId
                    resumeUrl = frame.d.resumeGatewayUrl
                    attempts = 0
                    _state.value = GatewayState.Connected(resumed = false)
                    listener.onReady(frame.d)
                    return
                }
                head.contains("\"t\":\"READY_SUPPLEMENTAL\"") -> {
                    val frame = DiscordJson.decodeFromStream(SupplementalFrame.serializer(), ByteArrayInputStream(payload))
                    frame.s?.let { seq = it }
                    listener.onReadySupplemental(frame.d)
                    return
                }
            }
            val obj = DiscordJson.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
            val op = (obj["op"] as? JsonPrimitive)?.intOrNull ?: return
            (obj["s"] as? JsonPrimitive)?.longOrNull?.let { seq = it }
            when (op) {
                Op.HELLO -> {
                    val interval = obj["d"]?.jsonObject?.get("heartbeat_interval")?.let { (it as JsonPrimitive).longOrNull } ?: 41_250L
                    startHeartbeat(gen, interval)
                    identifyOrResume()
                }
                Op.HEARTBEAT_ACK -> heartbeatAcked = true
                Op.HEARTBEAT -> sendHeartbeat()
                Op.RECONNECT -> fail(gen, 4000, "server asked to reconnect")
                Op.INVALID_SESSION -> {
                    val resumable = (obj["d"] as? JsonPrimitive)?.booleanOrNull == true
                    if (!resumable) {
                        sessionId = null
                        seq = null
                    }
                    fail(gen, 4000, "invalid session")
                }
                Op.DISPATCH -> {
                    val t = (obj["t"] as? JsonPrimitive)?.content ?: return
                    val d = obj["d"] ?: JsonNull
                    when (t) {
                        "RESUMED" -> {
                            attempts = 0
                            _state.value = GatewayState.Connected(resumed = true)
                            listener.onResumed()
                        }
                        // Only reached if the fast path above missed the type tag, e.g. the key
                        // order or spacing of the envelope ever changes.
                        "READY" -> {
                            val ready = DiscordJson.decodeFromJsonElement(Ready.serializer(), d)
                            sessionId = ready.sessionId
                            resumeUrl = ready.resumeGatewayUrl
                            attempts = 0
                            _state.value = GatewayState.Connected(resumed = false)
                            listener.onReady(ready)
                        }
                        "READY_SUPPLEMENTAL" ->
                            listener.onReadySupplemental(DiscordJson.decodeFromJsonElement(ReadySupplemental.serializer(), d))
                        else -> listener.onDispatch(t, d)
                    }
                }
            }
        } catch (t: Throwable) {
            // One malformed event must not take the connection down.
            System.err.println("WristCord gateway: dropped payload: ${t.message}")
        }
    }

    private fun identifyOrResume() {
        val token = token ?: return
        _state.value = GatewayState.Identifying
        if (isResumable) {
            send(Op.RESUME, buildJsonObject {
                put("token", token)
                put("session_id", sessionId)
                put("seq", seq)
            })
        } else {
            send(Op.IDENTIFY, buildJsonObject {
                put("token", token)
                put("capabilities", Discord.CAPABILITIES)
                put("properties", identity.superProperties)
                put("presence", presence())
                put("compress", false)
                putJsonObject("client_state") {
                    putJsonObject("guild_versions") {}
                }
            })
        }
    }

    private fun startHeartbeat(gen: Int, interval: Long) {
        heartbeatJob?.cancel()
        heartbeatAcked = true
        heartbeatJob = scope.launch {
            delay((interval * Random.nextDouble()).toLong())
            while (isActive && gen == generation.get()) {
                if (!heartbeatAcked) {
                    fail(gen, 4000, "heartbeat not acknowledged")
                    return@launch
                }
                heartbeatAcked = false
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        send(Op.HEARTBEAT, seq?.let(::JsonPrimitive) ?: JsonNull)
    }

    /** Client-side decision to drop this socket and come back. */
    private fun fail(gen: Int, code: Int, reason: String) {
        if (gen != generation.get()) return
        socket?.close(code, reason)
        closed(gen, code, reason)
    }

    private fun closed(gen: Int, code: Int, reason: String) {
        if (gen != generation.get()) return
        generation.incrementAndGet()
        heartbeatJob?.cancel()
        socket = null
        when (code) {
            4004 -> {
                _state.value = GatewayState.Fatal(code, "Discord rejected the login")
                wanted = false
                listener.onAuthenticationFailed()
                return
            }
            4010, 4011, 4012, 4013, 4014 -> {
                _state.value = GatewayState.Fatal(code, reason.ifBlank { "Gateway closed ($code)" })
                wanted = false
                return
            }
            4007, 4009 -> {
                sessionId = null
                seq = null
            }
        }
        if (!wanted) {
            _state.value = GatewayState.Idle
            return
        }
        attempts++
        val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(attempts - 1, 6)) + Random.nextLong(0, 750)
        _state.value = GatewayState.Waiting(attempts, System.currentTimeMillis() + backoff, reason.ifBlank { "closed ($code)" })
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoff)
            if (wanted && socket == null) open()
        }
    }

    /** Skips the backoff wait - used when the network comes back or the screen turns on. */
    fun retryNow() {
        if (!wanted || socket != null) return
        retryJob?.cancel()
        open()
    }

    @Serializable
    private class ReadyFrame(val s: Long? = null, val d: Ready)

    @Serializable
    private class SupplementalFrame(val s: Long? = null, val d: ReadySupplemental)

    private companion object {
        const val HEAD_BYTES = 96
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
