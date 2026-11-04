package com.wristcord.wear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wristcord.discord.Cdn
import com.wristcord.discord.state.Snapshot
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.VoiceState
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.voice.CallState
import com.wristcord.wear.voice.Headset
import com.wristcord.wear.voice.JoinBlocker
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.RowIconSize
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord

/**
 * A voice channel: who is in it, with Discord's mute/deafen flags and a speaking ring, and the
 * edge button that joins it - live only while a wireless headset is connected to the watch.
 */
@Composable
fun VoiceChannelScreen(
    snapshot: Snapshot,
    channelId: Long,
    call: CallState,
    speaking: Set<Long>,
    onOpenRemote: () -> Unit,
) {
    val c = discord
    val ctx = LocalContext.current
    val app = WristCordApp.of(ctx)
    val headset by remember { Headset.observe(ctx) }.collectAsStateWithLifecycle(initialValue = remember { Headset.current(ctx) })
    val channel = snapshot.channels[channelId]
    val states = snapshot.voiceIn(channelId)
    val here = app.voiceCall.currentChannel() == channelId
    val mine = snapshot.myVoice
    val elsewhere = mine != null && mine.sessionId != snapshot.mySessionId
    var confirmMove by remember { mutableStateOf(false) }
    // Re-evaluated whenever the headset comes or goes.
    val blocker = remember(headset, call, channelId) { app.voiceCall.blocker(channelId) }

    val join: () -> Unit = {
        app.voiceCall.join(channel?.guildId, channelId)
        onOpenRemote()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && app.voiceCall.blocker(channelId) == null) {
            if (elsewhere) confirmMove = true else join()
        }
    }
    val onEdge: () -> Unit = {
        when {
            here -> onOpenRemote()
            blocker == JoinBlocker.NO_MIC_PERMISSION -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            blocker != null -> Unit
            elsewhere -> confirmMove = true
            else -> join()
        }
    }
    val canPress = here || blocker == null || blocker == JoinBlocker.NO_MIC_PERMISSION

    ListScreen(
        edgeButton = {
            EdgeButton(
                onClick = onEdge,
                enabled = canPress,
                buttonSize = EdgeButtonSize.Medium,
                colors = if (here) ButtonDefaults.buttonColors(containerColor = c.controlConnected, contentColor = c.controlConnectedText)
                else ButtonDefaults.buttonColors(),
            ) {
                Icon(if (headset == null && !here) Icons.Rounded.HeadsetOff else Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    when {
                        here -> " In call"
                        elsewhere -> " Move here"
                        else -> " Join"
                    },
                    maxLines = 1,
                )
            }
        },
    ) { spec ->
        row(spec, "h") {
            ListHeader(modifier = modifier) {
                Text(channel?.let { snapshot.channelTitle(it) } ?: "Voice", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!here && blocker != null && blocker != JoinBlocker.NO_MIC_PERMISSION) row(spec, "why") {
            Caption(blocker.message, modifier, color = if (blocker == JoinBlocker.NO_HEADSET) c.textWarning else c.textMuted)
        } else if (!here && headset != null) row(spec, "headset") {
            Caption("Audio on ${Headset.name(headset!!)}", modifier)
        } else if (here && call is CallState.Live) row(spec, "e2ee") {
            Caption(encryptionLine(call), modifier, color = if (call.e2ee || call.securing) c.textMuted else c.textWarning)
        }
        if (states.isEmpty()) row(spec, "empty") { Caption("Nobody is here yet", modifier) }
        for (vs in states) row(spec, vs.userId) {
            val user = snapshot.users[vs.userId] ?: vs.member?.user
            val name = vs.member?.nick ?: user?.displayName ?: "Unknown"
            val talking = vs.userId in speaking
            Button(
                onClick = {},
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    Box(
                        Modifier
                            .size(RowIconSize + 4.dp)
                            .border(2.dp, if (talking) c.statusSpeaking else Color.Transparent, CircleShape),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Avatar(user?.let { Cdn.avatar(it, 64) }, name, RowIconSize)
                    }
                },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Row {
                        if (vs.selfDeaf || vs.deaf) Icon(Icons.Rounded.HeadsetOff, contentDescription = "Deafened", tint = c.iconVoiceMuted, modifier = Modifier.size(14.dp))
                        else if (vs.selfMute || vs.mute) Icon(Icons.Rounded.MicOff, contentDescription = "Muted", tint = c.iconVoiceMuted, modifier = Modifier.size(14.dp))
                        if (vs.selfVideo) Icon(Icons.Rounded.Videocam, contentDescription = "Camera on", tint = c.iconDefault, modifier = Modifier.size(14.dp))
                        if (vs.selfStream) Text(" LIVE", color = c.statusDanger)
                        if (vs.userId == snapshot.me?.id) Text(if (vs.sessionId == snapshot.mySessionId) " You · this watch" else " You", color = c.textMuted)
                    }
                },
            )
        }
    }

    AlertDialog(
        visible = confirmMove,
        onDismissRequest = { confirmMove = false },
        title = { Text("Move the call here?", textAlign = TextAlign.Center) },
        text = { Text("Your call moves from your other device to the watch and your headset.", textAlign = TextAlign.Center) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmMove = false
                join()
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmMove = false }) },
    )
}

/**
 * What Discord's apps show as the call's end-to-end encryption, with its voice privacy code: the
 * same 30 digits on every device in the call unless someone is in the middle. It changes when
 * people join or leave.
 */
private fun encryptionLine(call: CallState.Live): String = when {
    call.e2ee && call.privacyCode.isNotBlank() ->
        "End-to-end encrypted\n" + call.privacyCode.filter(Char::isDigit).chunked(5).chunked(3)
            .joinToString("\n") { it.joinToString(" ") }
    call.e2ee -> "End-to-end encrypted"
    call.securing -> "Securing the call…"
    else -> "Not end-to-end encrypted"
}

/** Which headset the watch's call plays on, when more than one is connected. */
@Composable
fun OutputsScreen(state: VoiceState, onChoose: (OutputDevice) -> Unit) {
    val c = discord
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Audio output") } }
        if (!state.onWatch) row(spec, "off") { Caption("Choose a headset once the call is on the watch.", modifier) }
        else if (state.outputs.isEmpty()) row(spec, "none") { Caption("No wireless headset connected.", modifier, color = c.textWarning) }
        for (device in state.outputs) row(spec, device.id) {
            Button(
                onClick = { onChoose(device) },
                modifier = modifier,
                transformation = transformation,
                colors = if (device.active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(if (device.active) Icons.Rounded.Check else Icons.Rounded.Headphones, contentDescription = null) },
                label = { Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (device.active) { { Text("In use") } } else null,
            )
        }
        row(spec, "rule") { Caption("Calls on the watch only ever play on a wireless headset.", modifier) }
    }
}
