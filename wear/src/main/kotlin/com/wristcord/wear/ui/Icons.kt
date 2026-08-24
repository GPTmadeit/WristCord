package com.wristcord.wear.ui

import androidx.annotation.DrawableRes
import com.wristcord.R
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState

/**
 * Icon per action, flipping to the struck-through variant when the thing is currently engaged -
 * the same convention Discord and every call UI uses.
 */
@DrawableRes
fun RemoteAction.icon(engaged: Boolean): Int = when (this) {
    RemoteAction.MUTE -> if (engaged) R.drawable.ic_mic_off else R.drawable.ic_mic
    RemoteAction.DEAFEN -> if (engaged) R.drawable.ic_headset_off else R.drawable.ic_headset
    RemoteAction.OUTPUT -> R.drawable.ic_output
    RemoteAction.DISCONNECT -> R.drawable.ic_call_end
    RemoteAction.REFRESH -> R.drawable.ic_refresh
    RemoteAction.NONE -> R.drawable.ic_empty_slot
}

/** Whether this action's "on" state is currently true, as far as the phone has told us. */
fun RemoteAction.engagedIn(state: VoiceState): Boolean = when (this) {
    RemoteAction.MUTE -> state.muted == Toggle.ON
    RemoteAction.DEAFEN -> state.deafened == Toggle.ON
    else -> false
}

/** Short line under the label: what the button will do next, not what it is called. */
fun RemoteAction.statusIn(state: VoiceState): String = when (this) {
    RemoteAction.MUTE -> when (state.muted) {
        Toggle.ON -> "Muted"
        Toggle.OFF -> "Live"
        Toggle.UNKNOWN -> "Unknown"
    }

    RemoteAction.DEAFEN -> when (state.deafened) {
        Toggle.ON -> "Deafened"
        Toggle.OFF -> "Hearing"
        Toggle.UNKNOWN -> "Unknown"
    }

    RemoteAction.OUTPUT -> state.outputs.firstOrNull { it.active }?.label
        ?: "${state.outputs.size} devices"

    RemoteAction.DISCONNECT -> "Leave call"
    RemoteAction.REFRESH -> "Resync"
    RemoteAction.NONE -> ""
}

/** Label shown on the big centre button, which flips to say what tapping will do. */
fun RemoteAction.centreLabel(state: VoiceState): String = when (this) {
    RemoteAction.MUTE -> if (state.muted == Toggle.ON) "Unmute" else "Mute"
    RemoteAction.DEAFEN -> if (state.deafened == Toggle.ON) "Undeafen" else "Deafen"
    else -> label
}
