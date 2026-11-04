package com.wristcord.wear.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wristcord.discord.Cdn
import com.wristcord.discord.state.Snapshot
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.RowIconSize
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord

/** Who is in a voice channel, with Discord's mute/deafen flags for each of them. */
@Composable
fun ParticipantsScreen(snapshot: Snapshot, channelId: Long) {
    val c = discord
    val channel = snapshot.channels[channelId]
    val states = snapshot.voiceIn(channelId)
    ListScreen { spec ->
        row(spec, "h") {
            ListHeader(modifier = modifier) {
                Text(channel?.let { snapshot.channelTitle(it) } ?: "Voice", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (states.isEmpty()) row(spec, "empty") { Caption("Nobody is here", modifier) }
        for (vs in states) row(spec, vs.userId) {
            val user = snapshot.users[vs.userId] ?: vs.member?.user
            val name = vs.member?.nick ?: user?.displayName ?: "Unknown"
            Button(
                onClick = {},
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Avatar(user?.let { Cdn.avatar(it, 64) }, name, RowIconSize) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Row {
                        if (vs.selfDeaf || vs.deaf) Icon(Icons.Rounded.HeadsetOff, contentDescription = "Deafened", tint = c.iconVoiceMuted, modifier = Modifier.size(14.dp))
                        else if (vs.selfMute || vs.mute) Icon(Icons.Rounded.MicOff, contentDescription = "Muted", tint = c.iconVoiceMuted, modifier = Modifier.size(14.dp))
                        if (vs.selfVideo) Icon(Icons.Rounded.Videocam, contentDescription = "Camera on", tint = c.iconDefault, modifier = Modifier.size(14.dp))
                        if (vs.selfStream) Text(" LIVE", color = c.statusDanger)
                        if (vs.userId == snapshot.me?.id) Text(" You", color = c.textMuted)
                    }
                },
            )
        }
    }
}

/** The phone's audio devices, from the phone bridge - switching drives Discord's own picker. */
@Composable
fun OutputsScreen(state: VoiceState, error: String?, pending: String?, onChoose: (OutputDevice) -> Unit) {
    val c = discord
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Audio output") } }
        if (error != null) row(spec, "err") { Caption(error, modifier, color = c.textCritical) }
        if (state.outputs.isEmpty()) row(spec, "none") {
            Caption("The phone has not reported any devices yet. Output switching needs WristCord on the phone.", modifier)
        }
        for (device in state.outputs) row(spec, device.id) {
            val busy = pending == Wire.PATH_OUTPUT
            Button(
                onClick = { onChoose(device) },
                modifier = modifier,
                transformation = transformation,
                colors = if (device.active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(if (device.active) Icons.Rounded.Check else Icons.Rounded.Speaker, contentDescription = null) },
                label = { Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = when {
                    busy -> { { Text("Sending…") } }
                    device.active -> { { Text("In use") } }
                    else -> null
                },
            )
        }
    }
}
