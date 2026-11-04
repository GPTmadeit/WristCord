package com.wristcord.shared

import androidx.compose.runtime.Immutable

/**
 * Tri-state, because "we could not tell" is a real answer: before the watch has heard from
 * Discord there is no mute state to show, and rendering "off" would lie.
 */
enum class Toggle { ON, OFF, UNKNOWN }

/** One audio device the watch's call can play on. Only wireless headsets are ever offered. */
@Immutable
data class OutputDevice(
    val id: Int,
    val type: Int,
    val label: String,
    val active: Boolean,
)

/**
 * What the remote, tile, complication and watch-face chip render. Built from Discord's own
 * voice state (and, when the call runs on the watch, from the call itself).
 */
@Immutable
data class VoiceState(
    val inCall: Boolean = false,
    val channel: String = "",
    val muted: Toggle = Toggle.UNKNOWN,
    val deafened: Toggle = Toggle.UNKNOWN,
    val outputs: List<OutputDevice> = emptyList(),
    /** Why the buttons cannot act right now, or "" when they can. */
    val blocker: String = "",
    /** The call is running on this watch, so the buttons act on it directly. */
    val onWatch: Boolean = false,
    /** The call is somewhere else and a headset is connected: it can be moved here. */
    val canMoveHere: Boolean = false,
    val updatedAt: Long = 0L,
)
