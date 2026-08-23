package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.wristcord.wear.link.Commands
import com.wristcord.wear.link.StateStore

private enum class Screen { REMOTE, OUTPUTS }

@Composable
fun WearApp(openOutputs: Boolean, onOutputsConsumed: () -> Unit) {
    val state by StateStore.state.collectAsState()
    val error by StateStore.error.collectAsState()
    val pending by StateStore.pending.collectAsState()
    var screen by remember { mutableStateOf(Screen.REMOTE) }

    LaunchedEffect(openOutputs) {
        if (openOutputs) {
            screen = Screen.OUTPUTS
            onOutputsConsumed()
        }
    }

    MaterialTheme {
        AppScaffold {
            when (screen) {
                Screen.REMOTE -> RemoteScreen(
                    state = state,
                    error = error,
                    pending = pending,
                    onOpenOutputs = { screen = Screen.OUTPUTS },
                )

                Screen.OUTPUTS -> SwipeToDismissBox(onDismissed = { screen = Screen.REMOTE }) { isBackground ->
                    if (isBackground) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    } else {
                        OutputsScreen(
                            state = state,
                            error = error,
                            pending = pending,
                            onChosen = { screen = Screen.REMOTE },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteScreen(
    state: VoiceState,
    error: String?,
    pending: String?,
    onOpenOutputs: () -> Unit,
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text(state.channel.ifBlank { if (state.inCall) "In a call" else "WristCord" }) }

            Banner(error = error, state = state)

            ToggleButton(
                label = if (state.muted == Toggle.ON) "Unmute" else "Mute",
                status = state.muted.describe(on = "Muted", off = "Live"),
                active = state.muted == Toggle.ON,
                busy = pending == Wire.PATH_MUTE,
                onClick = { Commands.mute(ctx) },
            )

            ToggleButton(
                label = if (state.deafened == Toggle.ON) "Undeafen" else "Deafen",
                status = state.deafened.describe(on = "Deafened", off = "Hearing"),
                active = state.deafened == Toggle.ON,
                busy = pending == Wire.PATH_DEAFEN,
                onClick = { Commands.deafen(ctx) },
            )

            Button(
                onClick = onOpenOutputs,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                secondaryLabel = {
                    Text(state.outputs.firstOrNull { it.active }?.label ?: "${state.outputs.size} devices")
                },
                label = { Text("Output") },
            )

            Button(
                onClick = { Commands.disconnect(ctx) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    secondaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                secondaryLabel = if (pending == Wire.PATH_DISCONNECT) {
                    { Text("Sending...") }
                } else {
                    null
                },
                label = { Text("Disconnect") },
            )
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    status: String,
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = { Text(if (busy) "Sending..." else status) },
        label = { Text(label) },
    )
}

@Composable
private fun OutputsScreen(
    state: VoiceState,
    error: String?,
    pending: String?,
    onChosen: () -> Unit,
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text("Audio output") }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.outputs.isEmpty()) {
                Text(
                    "The phone has not reported any devices yet.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.outputs.forEach { device ->
                OutputRow(
                    device = device,
                    busy = pending == Wire.PATH_OUTPUT,
                    onClick = {
                        Commands.chooseOutput(ctx, device)
                        onChosen()
                    },
                )
            }
        }
    }
}

@Composable
private fun OutputRow(device: OutputDevice, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (device.active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = when {
            busy -> {
                { Text("Sending...") }
            }

            device.active -> {
                { Text("In use") }
            }

            else -> null
        },
        label = { Text(device.label) },
    )
}

/**
 * The watch never renders a failure as nothing happening: an error from the phone or from the
 * Data Layer sits at the top of the remote until the next successful command clears it.
 */
@Composable
private fun Banner(error: String?, state: VoiceState) {
    val message = error ?: state.blocker.ifBlank { null }
        ?: if (!state.inCall) "Not in a voice call" else null
    if (message == null) return

    val isError = error != null || state.blocker.isNotBlank()
    Text(
        text = message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

private fun Toggle.describe(on: String, off: String): String = when (this) {
    Toggle.ON -> on
    Toggle.OFF -> off
    Toggle.UNKNOWN -> "State unknown"
}
