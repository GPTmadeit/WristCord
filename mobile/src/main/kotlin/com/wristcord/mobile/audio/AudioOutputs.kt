package com.wristcord.mobile.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Context
import com.wristcord.shared.OutputDevice

/**
 * Enumerates the phone's usable voice-call outputs for the watch's Output picker.
 *
 * Listing is all this does. Actually switching Discord's audio goes through Discord's own
 * output control via [com.wristcord.mobile.discord.A11yStrategy.chooseOutput], NOT through
 * AudioManager.setCommunicationDevice(): that API sets the communication device for the
 * *calling* app only (it is scoped to our uid since Android 12). Calling it here would move
 * WristCord's own - nonexistent - voice audio and leave Discord routed exactly where it was,
 * i.e. it would fail silently, which is the one thing this app is not allowed to do.
 */
object AudioOutputs {

    fun list(ctx: Context): List<OutputDevice> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return emptyList()
        val activeId = runCatching { am.communicationDevice?.id }.getOrNull()
        val devices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
        return devices
            .map { info ->
                OutputDevice(
                    id = info.id,
                    type = info.type,
                    label = label(info),
                    active = info.id == activeId,
                )
            }
            .distinctBy { it.label }
    }

    private fun label(info: AudioDeviceInfo): String {
        val product = runCatching { info.productName?.toString().orEmpty() }.getOrDefault("")
        val generic = typeName(info.type)
        // A Bluetooth headset's product name is the useful bit; the built-in ones just repeat
        // the phone's model, so prefer the generic word there.
        return when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> product.ifBlank { generic }

            else -> generic
        }
    }

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio speaker"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> "Audio device $type"
    }

    /**
     * Words Discord is likely to use for this device in its own picker, so an accessibility
     * match still lands when Discord names things differently to AudioManager.
     */
    fun synonyms(type: Int): List<String> = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> listOf("Earpiece", "Phone", "Handset")
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> listOf("Speaker", "Speakerphone", "Loudspeaker")
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> listOf("Wired", "Headphones", "Headset")

        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> listOf("USB", "Headset")
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> listOf("Bluetooth")

        AudioDeviceInfo.TYPE_HEARING_AID -> listOf("Hearing aid", "Bluetooth")
        else -> emptyList()
    }
}
