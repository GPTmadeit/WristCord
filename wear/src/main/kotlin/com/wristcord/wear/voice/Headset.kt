package com.wristcord.wear.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The rule for voice on the watch: **a wireless headset or nothing.** A call never plays out of
 * the watch's own speaker: joining is only offered while a Bluetooth headset is connected, and
 * losing it mid-call mutes and deafens at once. The headset's mic is used when Android allows
 * call audio to it; on a watch paired with a phone it refuses that for classic headsets, and
 * the call then plays as media audio on the headset with the watch's mic ([AudioPath.MEDIA]).
 *
 * "Headset" means a device Android can route a call to: classic Bluetooth (HFP/SCO), LE Audio
 * headsets, and Bluetooth hearing aids. A2DP-only speakers cannot carry a call and do not count.
 */
object Headset {

    private val WIRELESS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun isWireless(d: AudioDeviceInfo) = d.type in WIRELESS

    /** Outputs a headset can take media audio on, the fallback when call audio is refused. */
    private val MEDIA_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** The media-audio side of [headset] (same device, its A2DP output), if it has one. */
    fun mediaOutput(ctx: Context, headset: AudioDeviceInfo): AudioDeviceInfo? {
        val outputs = audio(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in MEDIA_OUTPUTS }
        return outputs.firstOrNull { it.address.isNotEmpty() && it.address == headset.address }
            ?: outputs.firstOrNull { it.productName == headset.productName }
            ?: outputs.singleOrNull()
    }

    /** The watch's own speaker and mic: where a call must never end up. */
    private val BUILT_IN = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )

    fun isBuiltIn(d: AudioDeviceInfo) = d.type in BUILT_IN

    /** A short name for where audio is routed, for the call's diagnostics. */
    fun describe(d: AudioDeviceInfo?): String = when (d?.type) {
        null -> "no route yet"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media audio"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "watch speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "watch earpiece"
        else -> "device type ${d.type}"
    }

    /** Every wireless headset the call could be routed to right now. */
    fun available(ctx: Context): List<AudioDeviceInfo> =
        audio(ctx).availableCommunicationDevices.filter(::isWireless)

    fun current(ctx: Context): AudioDeviceInfo? = available(ctx).firstOrNull()

    fun name(d: AudioDeviceInfo): String = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: when (d.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        else -> "Bluetooth headset"
    }

    /** Emits the first available headset (or null) now and on every connect/disconnect. */
    fun observe(ctx: Context): Flow<AudioDeviceInfo?> = callbackFlow {
        val am = audio(ctx)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(current(ctx)) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(current(ctx)) }
        }
        trySend(current(ctx))
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { am.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged { a, b -> a?.id == b?.id }

    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)
}
