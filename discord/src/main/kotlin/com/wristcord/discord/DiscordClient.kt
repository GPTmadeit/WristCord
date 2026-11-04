package com.wristcord.discord

import com.wristcord.discord.gateway.Gateway
import com.wristcord.discord.model.ChannelType
import com.wristcord.discord.model.Emoji
import com.wristcord.discord.model.Ready
import com.wristcord.discord.model.ReadySupplemental
import com.wristcord.discord.proto.Frecency
import com.wristcord.discord.proto.UserSettings
import com.wristcord.discord.remoteauth.RemoteAuth
import com.wristcord.discord.rest.DiscordRest
import com.wristcord.discord.state.DiscordState
import com.wristcord.discord.state.PendingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The whole Discord client behind one object: gateway, REST, QR login and live state. The
 * Android layer owns its lifetime (connect while visible, pause when backgrounded) and the
 * token's storage; everything Discord-shaped lives here.
 */
class DiscordClient(
    val identity: ClientIdentity,
    private val scope: CoroutineScope,
    baseHttp: OkHttpClient = defaultHttp(),
) {
    /** REST and image loading share this pool. */
    val http: OkHttpClient = baseHttp
    private val socketHttp = baseHttp.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    val state = DiscordState()

    @Volatile var token: String? = null
        private set

    /** Discord can rotate the token in READY (AUTH_TOKEN_REFRESH); the app must persist it. */
    var onTokenRefreshed: ((String) -> Unit)? = null
    /** The token was rejected (gateway 4004 or REST 401). */
    var onSignedOut: (() -> Unit)? = null

    val rest = DiscordRest(http, identity) { token }

    val gateway = Gateway(socketHttp, identity, scope, object : Gateway.Listener {
        override fun onReady(ready: Ready) {
            state.onReady(ready)
            ready.authToken?.takeIf { it.isNotBlank() && it != token }?.let {
                token = it
                onTokenRefreshed?.invoke(it)
            }
            scope.launch { refreshFrecency() }
        }

        override fun onReadySupplemental(supplemental: ReadySupplemental) = state.onReadySupplemental(supplemental)

        override fun onDispatch(type: String, data: JsonElement) = state.onDispatch(type, data)

        override fun onAuthenticationFailed() {
            onSignedOut?.invoke()
        }
    })

    fun newRemoteAuth(): RemoteAuth = RemoteAuth(socketHttp, rest, scope)

    // ---- lifecycle ---------------------------------------------------------------------------

    fun start(token: String) {
        this.token = token
        gateway.connect(token)
    }

    /** App went to the background: keep the session resumable for a quick return. */
    fun pause() = gateway.disconnect(resumable = true)

    fun resume() {
        val t = token ?: return
        gateway.connect(t)
    }

    suspend fun signOut(revoke: Boolean) {
        if (revoke) runCatching { rest.logout() }
        gateway.disconnect(resumable = false)
        state.clear()
        token = null
    }

    // ---- actions -----------------------------------------------------------------------------

    suspend fun loadMessages(channelId: Long, older: Boolean = false) {
        val current = state.messages.peek(channelId)
        if (current?.loading == true) return
        state.messages.mutate(channelId) { it.copy(loading = true, error = null) }
        try {
            val before = if (older) current?.messages?.firstOrNull()?.id else null
            val page = rest.messages(channelId, before = before, limit = PAGE)
            for (m in page) m.author?.let { a -> state.update { s -> if (s.users[a.id] == a) s else s.copy(users = s.users.put(a.id, a)) } }
            state.messages.loaded(channelId, page, older, PAGE)
        } catch (t: Throwable) {
            state.messages.mutate(channelId) { it.copy(loading = false, error = t.message ?: "Could not load messages") }
        }
    }

    /** Opening a channel: stream it live and mark it read everywhere. */
    fun openChannel(channelId: Long) {
        val ch = state.value.channels[channelId] ?: return
        ch.guildId?.let { gateway.subscribe(it, channelId) }
        scope.launch { ack(channelId) }
    }

    private var ackJob: Job? = null

    /** Debounced: a busy channel acks once per settle, not once per message. */
    fun ackSoon(channelId: Long) {
        ackJob?.cancel()
        ackJob = scope.launch {
            delay(ACK_DEBOUNCE_MS)
            ack(channelId)
        }
    }

    suspend fun ack(channelId: Long) {
        val snap = state.value
        val ch = snap.channels[channelId] ?: return
        val last = ch.lastMessageId ?: return
        if (!snap.isUnread(channelId) && snap.mentions(channelId) == 0) return
        state.markReadLocally(channelId, last)
        runCatching {
            rest.ack(channelId, last, guildChannel = ch.guildId != null, thread = ch.type in THREADS)
        }
    }

    suspend fun send(channelId: Long, content: String, replyTo: Long? = null): Boolean {
        val me = state.value.me ?: return false
        val text = content.trim()
        if (text.isEmpty()) return false
        val nonce = Snowflake.nonce()
        state.messages.addPending(PendingMessage(nonce, channelId, text, me, replyTo))
        return try {
            val guildId = state.value.channels[channelId]?.guildId
            val sent = rest.sendMessage(channelId, text, replyTo, guildId, nonce)
            // The gateway echo usually beats the HTTP response; whichever lands first wins.
            state.messages.onCreate(sent, me.id)
            state.update { s ->
                val c = s.channels[channelId]
                if (c == null) s else s.copy(
                    channels = s.channels.put(channelId, c.copy(lastMessageId = maxOf(sent.id, c.lastMessageId ?: 0))),
                    readStates = s.readStates.put(channelId, com.wristcord.discord.state.ReadState(sent.id, 0)),
                )
            }
            true
        } catch (t: Throwable) {
            state.messages.failPending(channelId, nonce, t.message ?: "Not sent")
            false
        }
    }

    suspend fun react(channelId: Long, messageId: Long, emoji: Emoji, add: Boolean): Boolean {
        state.messages.react(channelId, messageId, emoji, if (add) 1 else -1, byMe = true)
        return try {
            if (add) rest.addReaction(channelId, messageId, emoji) else rest.removeReaction(channelId, messageId, emoji)
            true
        } catch (t: Throwable) {
            state.messages.react(channelId, messageId, emoji, if (add) -1 else 1, byMe = true)
            false
        }
    }

    fun typing(channelId: Long) {
        scope.launch { runCatching { rest.typing(channelId) } }
    }

    /** Online status through the settings proto, so every signed-in device shows it. */
    suspend fun setStatus(status: String): Boolean = try {
        val full = rest.patchSettingsProto(1, UserSettings.statusPatch(status))
        state.applySettingsBase64(full)
        gateway.updatePresence(status = status)
        true
    } catch (t: Throwable) {
        false
    }

    suspend fun setCustomStatus(text: String, emojiName: String = "", expiresAtMs: Long = 0): Boolean = try {
        val full = rest.patchSettingsProto(1, UserSettings.customStatusPatch(text, emojiName, 0, expiresAtMs))
        state.applySettingsBase64(full)
        true
    } catch (t: Throwable) {
        false
    }

    suspend fun refreshFrecency() {
        runCatching { state.setFrecency(Frecency.parseBase64(rest.settingsProto(2))) }
    }

    /**
     * Op 4 with no channel. Discord's apps never do this for another device's call, so it is
     * offered as a fallback only, when the phone bridge cannot reach the phone.
     */
    fun leaveVoiceViaGateway(): Boolean {
        val v = state.value.myVoice ?: return false
        gateway.voiceStateUpdate(v.guildId, null, v.selfMute, v.selfDeaf)
        return true
    }

    companion object {
        const val PAGE = 30
        const val ACK_DEBOUNCE_MS = 1_500L
        private val THREADS = setOf(ChannelType.PUBLIC_THREAD, ChannelType.PRIVATE_THREAD, ChannelType.ANNOUNCEMENT_THREAD)

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
