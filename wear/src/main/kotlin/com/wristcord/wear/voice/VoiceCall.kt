package com.wristcord.wear.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.core.content.ContextCompat
import com.wristcord.discord.gateway.GatewayState
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.VoiceState
import com.wristcord.discord.state.StoreEvent
import com.wristcord.discord.voice.VoiceConnection
import com.wristcord.discord.voice.VoiceLinkState
import com.wristcord.discord.voice.VoiceServerInfo
import com.wristcord.discord.voice.VoiceStats
import com.wristcord.wear.WristCordApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface CallState {
    data object Idle : CallState
    data class Joining(val guildId: Long?, val channelId: Long, val step: String) : CallState
    data class Live(
        val guildId: Long?,
        val channelId: Long,
        val muted: Boolean,
        val deafened: Boolean,
        val e2ee: Boolean,
        val privacyCode: String,
        /** DAVE is still being set up; speech is held back until it is. */
        val securing: Boolean = false,
        val headset: String,
        val reconnecting: Boolean = false,
        /** The headset dropped: everything is muted until it is back or the call ends. */
        val headsetLost: Boolean = false,
        /** Media audio to the headset, so you speak into the watch (see [AudioPath.MEDIA]). */
        val watchMic: Boolean = false,
    ) : CallState
    /** Shown for a moment after a call ends for a reason the user did not choose. */
    data class Ended(val reason: String) : CallState
}

/** Live counters for the watch's call: where audio stops, if it does. */
data class CallDiagnostics(val link: VoiceStats, val audio: AudioStats, val network: String)

/** Why the watch will not join right now, or null when it will. */
enum class JoinBlocker(val message: String) {
    NO_HEADSET("Connect a Bluetooth headset to the watch to join"),
    NO_MIC_PERMISSION("Allow the microphone to join"),
    NO_E2EE("End-to-end encryption failed to load on this watch"),
    NOT_SIGNED_IN("Sign in first"),
    STAGE("Stage channels open on your phone"),
}

/**
 * The watch's own voice connection. Joining asks Discord's gateway to put this session in the
 * channel (op 4), waits for Discord to answer with our voice state and a voice server, then
 * connects to that server with DAVE end-to-end encryption and routes audio to the headset.
 */
class VoiceCall(private val app: WristCordApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state

    private val _diagnostics = MutableStateFlow<CallDiagnostics?>(null)
    /** Updated every second while a call is on the watch. */
    val diagnostics: StateFlow<CallDiagnostics?> = _diagnostics

    private val _speaking = MutableStateFlow<Set<Long>>(emptySet())
    /** User ids heard in the last moment, including you while you talk. */
    val speaking: StateFlow<Set<Long>> = _speaking

    private var connection: VoiceConnection? = null
    private var engine: AudioEngine? = null
    private var network: CallNetwork? = null
    /** Everything belonging to one call; cancelled as a whole when it ends. */
    private var call: CoroutineScope? = null
    private var device: AudioDeviceInfo? = null
    private val ssrcUsers = ConcurrentHashMap<Int, Long>()

    @Volatile private var muted = false
    @Volatile private var deafened = false
    private var mutedBeforeDeafen = false

    fun blocker(channelId: Long?): JoinBlocker? {
        val snap = app.discord.state.value
        if (snap.me == null) return JoinBlocker.NOT_SIGNED_IN
        if (channelId != null && snap.channels[channelId]?.type == ChannelType.GUILD_STAGE_VOICE) return JoinBlocker.STAGE
        if (!NativeDave.available) return JoinBlocker.NO_E2EE
        if (Headset.current(app) == null) return JoinBlocker.NO_HEADSET
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return JoinBlocker.NO_MIC_PERMISSION
        }
        return null
    }

    val isOnWatch: Boolean get() = _state.value is CallState.Live || _state.value is CallState.Joining

    fun currentChannel(): Long? = when (val s = _state.value) {
        is CallState.Live -> s.channelId
        is CallState.Joining -> s.channelId
        else -> null
    }

    fun join(guildId: Long?, channelId: Long) {
        if (currentChannel() == channelId) return
        if (blocker(channelId) != null) return
        // Switching channels: op 4 with the new channel moves this session; no need to leave.
        teardown(sendLeave = false)
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        call = cs
        cs.launch {
            runCatching { runCall(cs, guildId, channelId) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                Log.e(TAG, "call failed", it)
                end(it.message ?: "Could not join the call", sendLeave = true)
            }
        }
    }

    private suspend fun runCall(cs: CoroutineScope, guildId: Long?, channelId: Long) {
        val client = app.discord
        val me = client.state.value.me ?: error("Not signed in")
        val headset = Headset.current(app) ?: error(JoinBlocker.NO_HEADSET.message)
        device = headset
        _state.value = CallState.Joining(guildId, channelId, "Connecting")
        VoiceService.start(app)
        app.account.acquire(HOLD)

        withTimeoutOrNull(20_000) { client.gateway.state.first { it is GatewayState.Connected } }
            ?: error("Not connected to Discord")

        val net = CallNetwork(app).also { network = it }
        val netAsync = cs.async { net.acquire() }

        val server = CompletableDeferred<StoreEvent.VoiceServer>()
        val mine = CompletableDeferred<VoiceState>()
        // Subscribed before op 4 goes out, so even an instant answer is not missed.
        val waiter = cs.launch(start = CoroutineStart.UNDISPATCHED) {
            client.state.events.collect { e ->
                when (e) {
                    is StoreEvent.VoiceServer -> if (e.endpoint != null && (e.guildId == guildId || (guildId == null && e.channelId == channelId))) server.complete(e)
                    is StoreEvent.MyVoiceState -> if (e.state.channelId == channelId && e.state.sessionId == client.state.value.mySessionId) mine.complete(e.state)
                    else -> Unit
                }
            }
        }
        _state.value = CallState.Joining(guildId, channelId, "Asking Discord")
        client.gateway.voiceStateUpdate(guildId, channelId, muted, deafened)
        val srv = withTimeoutOrNull(15_000) { server.await() }
        val vs = withTimeoutOrNull(15_000) { mine.await() }
        waiter.cancel()
        if (srv == null || vs == null) error("Discord did not open a voice server")
        val bound = netAsync.await()

        _state.value = CallState.Joining(guildId, channelId, "Securing the call")
        val http = client.http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .apply { bound?.let { socketFactory(it.socketFactory) } }
            .build()
        val audio = AudioEngine(app, onFrame = { connection?.sendOpus(it) }, onQuiet = { connection?.stopTransmitting() })
        engine = audio
        val conn = connect(cs, VoiceServerInfo(guildId ?: channelId, channelId, me.id, vs.sessionId ?: client.state.value.mySessionId.orEmpty(), srv.token, srv.endpoint!!), http, bound)
        val first = withTimeoutOrNull(20_000) { conn.state.first { it is VoiceLinkState.Connected || it is VoiceLinkState.Failed } }
        if (first !is VoiceLinkState.Connected) error((first as? VoiceLinkState.Failed)?.reason ?: "Voice server did not answer")

        audio.start(headset)
        applyLocal()
        publishLive(conn.state.value)
        supervise(cs, guildId, channelId, http, bound)
    }

    private fun connect(cs: CoroutineScope, info: VoiceServerInfo, http: okhttp3.OkHttpClient, bound: android.net.Network?): VoiceConnection {
        connection?.close()
        val conn = VoiceConnection(
            info = info,
            http = http,
            scope = cs,
            daveFactory = { pv, uid, cid -> NativeDave.create(pv, uid, cid) },
            maxDaveVersion = NativeDave.maxProtocolVersion,
            xchachaFactory = NativeDave::xchacha,
            bindSocket = { s -> bound?.bindSocket(s) },
            // Who the main gateway shows in the channel: the members an MLS welcome may name.
            recognizedUsers = { app.discord.state.value.voiceIn(info.channelId).map { it.userId } },
            listener = object : VoiceConnection.Listener {
                override fun onOpus(userId: Long?, ssrc: Int, sequence: Int, opus: ByteArray) {
                    userId?.let { ssrcUsers[ssrc] = it }
                    engine?.onRemote(ssrc, sequence, opus)
                }

                override fun onSpeaking(userId: Long, ssrc: Int, speaking: Boolean) {
                    ssrcUsers[ssrc] = userId
                }

                override fun onClientDisconnect(userId: Long) {
                    ssrcUsers.entries.removeIf { it.value == userId }
                }

                override fun log(message: String) {
                    Log.i(TAG, message)
                }
            },
        )
        connection = conn
        conn.connect()
        return conn
    }

    private fun supervise(cs: CoroutineScope, guildId: Long?, channelId: Long, http: okhttp3.OkHttpClient, bound: android.net.Network?) {
        val client = app.discord
        val conn = connection ?: return
        cs.launch {
            conn.state.collect { s ->
                when (s) {
                    is VoiceLinkState.Failed -> end(s.reason, sendLeave = true)
                    is VoiceLinkState.Connected, is VoiceLinkState.Reconnecting -> publishLive(s)
                    else -> Unit
                }
            }
        }
        // Headset gone: mute and deafen this instant, leave if it is not back in 10 s.
        cs.launch {
            var leaving: Job? = null
            Headset.observe(app).collect { d ->
                if (d == null) {
                    engine?.micEnabled = false
                    engine?.playbackEnabled = false
                    updateLive { it.copy(headsetLost = true) }
                    // A fresh 10 s for every drop, so reconnecting and dropping again does not
                    // inherit the first timer.
                    leaving?.cancel()
                    leaving = launch {
                        delay(HEADSET_GRACE_MS)
                        if (Headset.current(app) == null) end("Headset disconnected", sendLeave = true)
                    }
                } else {
                    leaving?.cancel()
                    leaving = null
                    device = d
                    engine?.route(d)
                    // State first: applyLocal reads headsetLost, and must see it cleared.
                    updateLive { it.copy(headsetLost = false, headset = Headset.name(d)) }
                    applyLocal()
                }
            }
        }
        // Discord's view of this account: moved to another device, kicked, or moved channel.
        cs.launch {
            client.state.events.collect { e ->
                when (e) {
                    is StoreEvent.MyVoiceState -> {
                        val vs = e.state
                        if (vs.sessionId != null && vs.sessionId != client.state.value.mySessionId) end("Call moved to another device", sendLeave = false)
                        else if (vs.channelId == null) end("Disconnected from the call", sendLeave = false)
                    }
                    is StoreEvent.VoiceServer -> if (e.endpoint != null && (e.guildId == guildId || (guildId == null && e.channelId == channelId))) {
                        // Discord moved the call to another voice server.
                        val me = client.state.value.me ?: return@collect
                        val now = _state.value as? CallState.Live ?: return@collect
                        connect(cs, VoiceServerInfo(guildId ?: now.channelId, now.channelId, me.id, client.state.value.mySessionId.orEmpty(), e.token, e.endpoint!!), http, bound)
                            .also { c -> cs.launch { c.state.collect { s -> if (s is VoiceLinkState.Failed) end(s.reason, true) else if (s is VoiceLinkState.Connected) publishLive(s) } } }
                    }
                    else -> Unit
                }
            }
        }
        // Diagnostics: on screen every second, in the log every five.
        val networkName = if (bound != null) "Wi-Fi/LTE" else "default network"
        cs.launch {
            var tick = 0
            while (true) {
                val link = connection?.stats() ?: VoiceStats()
                val audio = engine?.stats() ?: AudioStats()
                _diagnostics.value = CallDiagnostics(link, audio, networkName)
                if (tick++ % 5 == 0) Log.i(TAG, "stats: $link | $audio | $networkName")
                delay(1_000)
            }
        }
        cs.launch {
            val audio = engine ?: return@launch
            kotlinx.coroutines.flow.combine(audio.speaking, audio.talking) { ssrcs, me -> ssrcs to me }.collect { (ssrcs, me) ->
                val ids = ssrcs.mapNotNull { ssrcUsers[it] }.toMutableSet()
                if (me && !muted) client.state.value.me?.id?.let { ids += it }
                _speaking.value = ids
            }
        }
    }

    private fun publishLive(link: VoiceLinkState) {
        val s = _state.value
        val guildId = (s as? CallState.Live)?.guildId ?: (s as? CallState.Joining)?.guildId
        val channelId = (s as? CallState.Live)?.channelId ?: (s as? CallState.Joining)?.channelId ?: return
        val connected = link as? VoiceLinkState.Connected
        _state.value = CallState.Live(
            guildId = guildId,
            channelId = channelId,
            muted = muted,
            deafened = deafened,
            e2ee = connected?.e2ee ?: (s as? CallState.Live)?.e2ee ?: false,
            privacyCode = connected?.privacyCode ?: (s as? CallState.Live)?.privacyCode.orEmpty(),
            securing = connected?.securing ?: (s as? CallState.Live)?.securing ?: false,
            headset = device?.let(Headset::name) ?: "Headset",
            reconnecting = link is VoiceLinkState.Reconnecting,
            headsetLost = (s as? CallState.Live)?.headsetLost ?: false,
            watchMic = engine?.path == AudioPath.MEDIA,
        )
    }

    private fun updateLive(f: (CallState.Live) -> CallState.Live) {
        val s = _state.value as? CallState.Live ?: return
        _state.value = f(s)
    }

    /** Debug builds' demo account only: a call that looks live on the watch, with nothing behind it. */
    fun showDemoCall(guildId: Long, channelId: Long) {
        _state.value = CallState.Live(
            guildId = guildId,
            channelId = channelId,
            muted = false,
            deafened = false,
            e2ee = true,
            privacyCode = "142857285714428571571428714285",
            headset = "Pixel Buds Pro 2",
            watchMic = true,
        )
        _diagnostics.value = CallDiagnostics(
            VoiceStats(sent = 1520, received = 1874, delivered = 1702, protocolVersion = 1, daveReady = true),
            AudioStats(captured = 3010, loud = 1530, encoded = 1520, decoded = 1702, played = 1650, micLevel = 2140,
                route = "Bluetooth media audio", path = AudioPath.MEDIA.label),
            "Wi-Fi/LTE",
        )
    }

    // ---- controls -----------------------------------------------------------------------------

    /** Unmuting while deafened undeafens too, as in Discord. */
    fun toggleMute() {
        if (deafened) {
            deafened = false
            muted = false
        } else {
            muted = !muted
        }
        applyAndTell()
    }

    /** Deafening mutes; undeafening restores whatever mute was before. */
    fun toggleDeafen() {
        if (!deafened) {
            mutedBeforeDeafen = muted
            deafened = true
            muted = true
        } else {
            deafened = false
            muted = mutedBeforeDeafen
        }
        applyAndTell()
    }

    fun route(output: AudioDeviceInfo) {
        device = output
        engine?.route(output)
        updateLive { it.copy(headset = Headset.name(output)) }
    }

    fun leave() {
        end(null, sendLeave = true)
    }

    private fun applyLocal() {
        val lost = (_state.value as? CallState.Live)?.headsetLost == true
        engine?.micEnabled = !muted && !lost
        engine?.playbackEnabled = !deafened && !lost
    }

    private fun applyAndTell() {
        applyLocal()
        updateLive { it.copy(muted = muted, deafened = deafened) }
        val s = _state.value as? CallState.Live ?: return
        // The same op 4, same channel: Discord shows the new flags to everyone in the call.
        app.discord.gateway.voiceStateUpdate(s.guildId, s.channelId, muted, deafened)
    }

    private fun end(reason: String?, sendLeave: Boolean) {
        val s = _state.value
        if (s is CallState.Idle || s is CallState.Ended) return
        teardown(sendLeave)
        _state.value = if (reason != null) CallState.Ended(reason) else CallState.Idle
        if (reason != null) scope.launch {
            delay(4_000)
            if (_state.value is CallState.Ended) _state.value = CallState.Idle
        }
    }

    private fun teardown(sendLeave: Boolean) {
        val s = _state.value
        val guildId = (s as? CallState.Live)?.guildId ?: (s as? CallState.Joining)?.guildId
        if (sendLeave && (s is CallState.Live || s is CallState.Joining)) {
            app.discord.gateway.voiceStateUpdate(guildId, null, muted, deafened)
        }
        val cs = call
        call = null
        connection?.close()
        connection = null
        engine?.stop()
        engine = null
        network?.release()
        network = null
        ssrcUsers.clear()
        _speaking.value = emptySet()
        _diagnostics.value = null
        if (s is CallState.Live || s is CallState.Joining) {
            app.account.release(HOLD)
            VoiceService.stop(app)
        }
        // Last: this may be the coroutine running right now.
        cs?.cancel()
    }

    private companion object {
        const val TAG = "WristCord/Call"
        const val HOLD = "voice"
        const val HEADSET_GRACE_MS = 10_000L
    }
}
