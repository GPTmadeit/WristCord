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
 * the watch's own speaker or listens through its own mic - joining is only offered while a
 * Bluetooth headset is connected, and losing it mid-call mutes and deafens at once.
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

    /** Where Android actually sent the call's audio: anything Bluetooth, never the watch itself. */
    private val WIRELESS_ROUTES = WIRELESS + setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    fun isWirelessRoute(d: AudioDeviceInfo) = d.type in WIRELESS_ROUTES

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
