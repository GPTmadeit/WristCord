package com.wristcord.wear.discord

import android.content.Context
import android.media.AudioDeviceInfo
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.voice.CallState
import com.wristcord.wear.voice.Headset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the remote, tile and complication show, from two sources:
 *
 *  - **the watch's own call** ([CallState]) when the watch is in voice - its buttons act on it;
 *  - **Discord's gateway** ([VoiceSnapshot]) for a call on your phone or desktop - shown as
 *    it is, with "move it here" on offer while a headset is connected.
 */
object Voice {

    private val _gateway = MutableStateFlow(VoiceSnapshot())
    val gateway: StateFlow<VoiceSnapshot> = _gateway

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        _gateway.value = VoiceSnapshot.read(ctx)
        loaded = true
    }

    fun publish(ctx: Context, v: VoiceSnapshot) {
        VoiceSnapshot.write(ctx, v)
        _gateway.value = v
        loaded = true
    }

    fun merge(
        gateway: VoiceSnapshot,
        call: CallState,
        signedIn: Boolean,
        headsets: List<AudioDeviceInfo>,
        routed: String?,
        channelName: (Long) -> String?,
        nowMs: Long = System.currentTimeMillis(),
    ): VoiceState = when (call) {
        is CallState.Live -> VoiceState(
            inCall = true,
            channel = channelName(call.channelId) ?: gateway.channel.ifBlank { "Voice" },
            muted = if (call.muted || call.deafened) Toggle.ON else Toggle.OFF,
            deafened = if (call.deafened) Toggle.ON else Toggle.OFF,
            outputs = headsets.map { OutputDevice(it.id, it.type, Headset.name(it), Headset.name(it) == routed) },
            blocker = when {
                call.headsetLost -> "Headset disconnected - leaving in a moment"
                call.reconnecting -> "Reconnecting…"
                else -> ""
            },
            onWatch = true,
            updatedAt = nowMs,
        )
        is CallState.Joining -> VoiceState(
            inCall = true,
            channel = channelName(call.channelId) ?: "Voice",
            blocker = call.step + "…",
            onWatch = true,
            updatedAt = nowMs,
        )
        else -> {
            val ended = (call as? CallState.Ended)?.reason
            if (signedIn && gateway.inCall && gateway.isFresh(nowMs) && gateway.device != "watch") {
                val where = when (gateway.device) {
                    "phone" -> "your phone"
                    "desktop" -> "your computer"
                    "web" -> "a browser"
                    "console" -> "a console"
                    else -> "another device"
                }
                VoiceState(
                    inCall = true,
                    channel = gateway.channel,
                    muted = if (gateway.muted) Toggle.ON else Toggle.OFF,
                    deafened = if (gateway.deafened) Toggle.ON else Toggle.OFF,
                    blocker = ended ?: "On $where",
                    canMoveHere = headsets.isNotEmpty(),
                    updatedAt = gateway.updatedAt,
                )
            } else {
                VoiceState(blocker = ended.orEmpty())
            }
        }
    }

    /** For the tile and complication, which may run before any screen has. */
    fun mergedSync(ctx: Context): VoiceState {
        val app = WristCordApp.of(ctx)
        val snap = app.discord.state.value
        return merge(
            gateway = if (loaded) _gateway.value else VoiceSnapshot.read(ctx),
            call = app.voiceCall.state.value,
            signedIn = TokenVault(ctx).hasToken,
            headsets = runCatching { Headset.available(ctx) }.getOrDefault(emptyList()),
            routed = (app.voiceCall.state.value as? CallState.Live)?.headset,
            channelName = { id -> snap.channels[id]?.let { snap.channelTitle(it) } },
        )
    }
}
