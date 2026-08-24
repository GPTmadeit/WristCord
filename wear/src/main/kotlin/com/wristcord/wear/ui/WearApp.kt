package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.wristcord.shared.Brand
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.wristcord.wear.link.ArtworkStore
import com.wristcord.wear.link.Commands
import com.wristcord.wear.link.OngoingService
import com.wristcord.wear.link.SlotConfig
import com.wristcord.wear.link.StateStore

private sealed interface Screen {
    data object Remote : Screen
    data object Outputs : Screen
    data object Settings : Screen
    data class SlotPicker(val slot: Slot) : Screen
    data object Updates : Screen
}

@Composable
fun WearApp(
    openOutputs: Boolean,
    updates: UpdateController,
    onOutputsConsumed: () -> Unit,
) {
    val ctx = LocalContext.current
    val state by StateStore.state.collectAsState()
    val error by StateStore.error.collectAsState()
    val pending by StateStore.pending.collectAsState()
    val assignments by SlotConfig.assignments.collectAsState()
    val artworkBitmap by ArtworkStore.bitmap.collectAsState()
    val artwork: ImageBitmap? = remember(artworkBitmap) { artworkBitmap?.asImageBitmap() }
    val updateStatus by updates.status.collectAsState()
    val ambient = rememberAmbientState()

    val stack = remember { mutableStateListOf<Screen>(Screen.Remote) }
    fun push(screen: Screen) = stack.add(screen)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    LaunchedEffect(openOutputs) {
        if (openOutputs) {
            if (stack.last() !is Screen.Outputs) push(Screen.Outputs)
            onOutputsConsumed()
        }
    }

    // Always-on shows the remote, the way YTM's ambient view always shows what is playing.
    LaunchedEffect(ambient.isAmbient) {
        if (ambient.isAmbient && stack.size > 1) {
            stack.removeRange(1, stack.size)
        }
    }

    val onAction: (RemoteAction) -> Unit = { action ->
        when {
            action == RemoteAction.NONE -> Unit
            action.opensList -> push(Screen.Outputs)
            action == RemoteAction.REFRESH -> Commands.refresh(ctx)
            action.path != null -> Commands.fire(ctx, action.path)
        }
    }

    WristCordTheme {
        AppScaffold(timeText = { if (!ambient.isAmbient) DefaultTimeText() }) {
            val current = stack.last()
            if (current is Screen.Remote) {
                Content(
                    screen = current,
                    state = state,
                    error = error,
                    pending = pending,
                    assignments = assignments,
                    ambient = ambient,
                    artwork = artwork,
                    updates = updates,
                    updateStatus = updateStatus,
                    onAction = onAction,
                    onPush = ::push,
                    onPop = ::pop,
                )
            } else {
                SwipeToDismissBox(onDismissed = ::pop) { isBackground ->
                    Content(
                        screen = if (isBackground) stack[stack.lastIndex - 1] else current,
                        state = state,
                        error = error,
                        pending = pending,
                        assignments = assignments,
                        ambient = ambient,
                        artwork = artwork,
                        updates = updates,
                        updateStatus = updateStatus,
                        onAction = onAction,
                        onPush = ::push,
                        onPop = ::pop,
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultTimeText() {
    androidx.wear.compose.material3.TimeText()
}

@Composable
private fun Content(
    screen: Screen,
    state: VoiceState,
    error: String?,
    pending: String?,
    assignments: Map<Slot, RemoteAction>,
    ambient: AmbientState,
    artwork: ImageBitmap?,
    updates: UpdateController,
    updateStatus: Updater.Status,
    onAction: (RemoteAction) -> Unit,
    onPush: (Screen) -> Unit,
    onPop: () -> Unit,
) {
    val ctx = LocalContext.current

    when (screen) {
        is Screen.Remote -> RemoteScreen(
            state = state,
            error = error,
            pending = pending,
            assignments = assignments,
            ambient = ambient,
            artwork = artwork,
            onAction = onAction,
            onOpenSettings = { onPush(Screen.Settings) },
        )

        is Screen.Outputs -> OutputsScreen(
            state = state,
            error = error,
            pending = pending,
            onChoose = { device ->
                Commands.chooseOutput(ctx, device)
                onPop()
            },
        )

        is Screen.Settings -> SettingsScreen(
            assignments = assignments,
            updateSummary = updates.summary(updateStatus),
            ongoingEnabled = OngoingService.isEnabled(ctx),
            onToggleOngoing = { OngoingService.setEnabled(ctx, it) },
            onEditSlot = { onPush(Screen.SlotPicker(it)) },
            onReset = { SlotConfig.reset(ctx) },
            onOpenUpdates = {
                updates.check()
                onPush(Screen.Updates)
            },
        )

        is Screen.SlotPicker -> SlotPickerScreen(
            slot = screen.slot,
            current = assignments[screen.slot] ?: screen.slot.default,
            onChoose = { action ->
                SlotConfig.assign(ctx, screen.slot, action)
                onPop()
            },
        )

        is Screen.Updates -> UpdateScreen(
            status = updateStatus,
            currentVersion = updates.currentVersionLabel,
            canInstall = Updater.canRequestInstalls(ctx),
            onCheck = { updates.check() },
            onInstall = { updates.install(it) },
        )
    }
}

@Composable
private fun OutputsScreen(
    state: VoiceState,
    error: String?,
    pending: String?,
    onChoose: (OutputDevice) -> Unit,
) {
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text("Audio output") }

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (state.outputs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                    Text(
                        "The phone has not reported any devices yet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(Brand.ON_SURFACE_VARIANT),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            state.outputs.forEach { device ->
                val busy = pending == Wire.PATH_OUTPUT
                Button(
                    onClick = { onChoose(device) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (device.active) {
                        ButtonDefaults.buttonColors(
                            containerColor = RemoteAction.OUTPUT.dimColor,
                            contentColor = Color(Brand.ON_SURFACE),
                            secondaryContentColor = Color(Brand.ON_SURFACE_VARIANT),
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (device.active) com.wristcord.R.drawable.ic_check
                                else com.wristcord.R.drawable.ic_output
                            ),
                            contentDescription = null,
                            tint = RemoteAction.OUTPUT.color,
                            modifier = Modifier.size(20.dp),
                        )
                    },
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
        }
    }
}
