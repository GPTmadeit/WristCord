package com.wristcord.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.wristcord.shared.RemoteAction
import com.wristcord.wear.ui.theme.DiscordColors
import com.wristcord.wear.ui.theme.discord

/**
 * The remote speaks Discord's voice-panel language: controls sit neutral until they are
 * engaged, and engaged mute/deafen turn red the way Discord shows a muted mic. Leave is always
 * the red hang-up.
 */
@Immutable
data class RemoteColors(val fill: Color, val tint: Color, val accent: Color)

@Composable
fun remoteColors(action: RemoteAction, engaged: Boolean): RemoteColors = remoteColors(discord, action, engaged)

fun remoteColors(c: DiscordColors, action: RemoteAction, engaged: Boolean): RemoteColors = when {
    action == RemoteAction.DISCONNECT -> RemoteColors(c.controlCritical, c.controlCriticalText, c.statusDanger)
    engaged && (action == RemoteAction.MUTE || action == RemoteAction.DEAFEN) ->
        RemoteColors(c.statusDanger, Color.White, c.statusDanger)
    action == RemoteAction.NONE -> RemoteColors(Color.Transparent, c.iconMuted, c.borderNormal)
    else -> RemoteColors(c.surfaceHighest, c.iconStrong, c.iconDefault)
}

/** The colour that stands for an action in lists and the slot editor. */
val RemoteAction.color: Color
    @Composable get() = remoteColors(this, engaged = false).accent
