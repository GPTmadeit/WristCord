package com.wristcord.wear.discord

import android.content.Context
import android.util.Log
import com.wristcord.discord.DiscordClient
import com.wristcord.discord.state.StoreEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the signed-in account on the watch: the token, when the gateway is connected, and
 * pushing voice state out to the tile and complication.
 *
 * The gateway is connected only while something is holding it - the app on screen, a tile
 * that was just looked at, or a call on the watch - and dropped a minute after the last holder
 * lets go. A watch that keeps a socket open all day is a watch that dies by dinner.
 */
class Account(
    private val ctx: Context,
    val client: DiscordClient,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
) {
    private val _signedIn = MutableStateFlow(vault.hasToken)
    val signedIn: StateFlow<Boolean> = _signedIn

    private val holders = HashSet<String>()
    private var graceJob: Job? = null

    init {
        client.onTokenRefreshed = { vault.save(it) }
        client.onSignedOut = {
            Log.w(TAG, "Discord rejected the token; signing out")
            scope.launch { signOut(revoke = false) }
        }
        scope.launch {
            client.state.events.collect { e ->
                when (e) {
                    is StoreEvent.VoiceChanged -> publishVoice()
                    is StoreEvent.SettingsChanged -> ThemePrefs.rememberAccountTheme(ctx, client.state.value.settings)
                    else -> Unit
                }
            }
        }
    }

    fun signedIn(token: String) {
        vault.save(token)
        _signedIn.value = true
        client.start(token)
    }

    /** Debug-only demo account: state is filled locally, no token and no gateway. */
    fun enterDemo() {
        _signedIn.value = true
        ThemePrefs.rememberAccountTheme(ctx, client.state.value.settings)
        publishVoice()
    }

    suspend fun signOut(revoke: Boolean) {
        client.signOut(revoke)
        vault.clear()
        VoiceSnapshot.clear(ctx)
        Voice.publish(ctx, VoiceSnapshot())
        _signedIn.value = false
        publishVoice()
    }

    /** Something needs live data. Connects (or resumes) right away. */
    @Synchronized
    fun acquire(reason: String) {
        graceJob?.cancel()
        holders += reason
        if (client.token != null) {
            client.resume()
            return
        }
        vault.load()?.let { client.start(it) }
    }

    @Synchronized
    fun release(reason: String) {
        holders -= reason
        if (holders.isNotEmpty()) return
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(GRACE_MS)
            synchronized(this@Account) { if (holders.isEmpty()) client.pause() }
        }
    }

    /** A short-lived hold, e.g. the tile was just swiped to. */
    fun touch(reason: String, forMs: Long = GRACE_MS) {
        acquire(reason)
        scope.launch {
            delay(forMs)
            release(reason)
        }
    }

    private fun publishVoice() {
        val snap = client.state.value
        val voice = if (_signedIn.value) VoiceSnapshot.from(snap) else VoiceSnapshot()
        val before = Voice.gateway.value
        Voice.publish(ctx, voice)
        if (voice.copy(updatedAt = 0) != before.copy(updatedAt = 0)) refreshSurfaces()
    }

    fun refreshSurfaces() = com.wristcord.wear.voice.Surfaces.refresh(ctx)

    private companion object {
        const val TAG = "WristCord/Account"
        const val GRACE_MS = 60_000L
    }
}
