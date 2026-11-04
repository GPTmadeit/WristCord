package com.wristcord.wear.discord

import android.content.Context
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Two sources of voice state meet here:
 *
 *  - **Discord's gateway** ([VoiceSnapshot]): the real `self_mute`/`self_deaf`, channel and
 *    server, wherever the call is running. Authoritative whenever it is fresh.
 *  - **The phone bridge** ([VoiceState] from the phone app): the audio-output list and whether
 *    the phone can act right now. Also the fallback when the watch is signed out or offline.
 *
 * The result keeps the shape of [VoiceState] so the remote, tile, complication and chip all
 * render one thing.
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

    fun merge(gateway: VoiceSnapshot, phone: VoiceState, signedIn: Boolean, nowMs: Long = System.currentTimeMillis()): VoiceState {
        if (!signedIn || !gateway.isFresh(nowMs)) return phone
        if (!gateway.inCall) return phone.copy(inCall = false, channel = "", muted = Toggle.UNKNOWN, deafened = Toggle.UNKNOWN, blocker = "")
        val blocker = when (gateway.device) {
            "phone", "" -> phone.blocker
            "watch" -> ""
            else -> "Call is on your ${gateway.device} - the buttons work when it is on your phone"
        }
        return phone.copy(
            inCall = true,
            channel = gateway.channel,
            muted = if (gateway.muted) Toggle.ON else Toggle.OFF,
            deafened = if (gateway.deafened) Toggle.ON else Toggle.OFF,
            blocker = blocker,
            updatedAt = gateway.updatedAt,
        )
    }

    /** Cold-process read for the tile and complication. */
    fun mergedSync(ctx: Context, phone: VoiceState): VoiceState =
        merge(VoiceSnapshot.read(ctx), phone, TokenVault(ctx).hasToken)
}
