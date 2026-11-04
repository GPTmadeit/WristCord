package com.wristcord.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.TimeText
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.discord.Voice
import com.wristcord.wear.link.ArtworkStore
import com.wristcord.wear.link.Commands
import com.wristcord.wear.link.OngoingService
import com.wristcord.wear.link.SlotConfig
import com.wristcord.wear.link.StateStore
import com.wristcord.wear.ui.theme.WristCordTheme
import kotlinx.coroutines.launch

sealed interface Screen {
    val key: String
    data object Home : Screen { override val key = "home" }
    data object Login : Screen { override val key = "login" }
    data object Dms : Screen { override val key = "dms" }
    data class Guild(val id: Long) : Screen { override val key = "guild-$id" }
    data class Chat(val channelId: Long, val replyTo: Long? = null) : Screen { override val key = "chat-$channelId" }
    data class Message(val channelId: Long, val messageId: Long) : Screen { override val key = "msg-$messageId" }
    data object Remote : Screen { override val key = "remote" }
    data class Participants(val channelId: Long) : Screen { override val key = "people-$channelId" }
    data object Outputs : Screen { override val key = "outputs" }
    data object Settings : Screen { override val key = "settings" }
    data object Status : Screen { override val key = "status" }
    data object Theme : Screen { override val key = "theme" }
    data object Buttons : Screen { override val key = "buttons" }
    data class SlotPicker(val slot: Slot) : Screen { override val key = "slot-${slot.id}" }
    data object Updates : Screen { override val key = "updates" }
}

/** Where a launch intent (tile, chip, complication) asked to land. */
enum class Destination { NONE, REMOTE, OUTPUTS }

@Composable
fun WearApp(
    destination: Destination,
    updates: UpdateController,
    onDestinationConsumed: () -> Unit,
    /** Debug builds only: screens to open on top of the root, for demo screenshots. */
    debugStack: List<Screen> = emptyList(),
) {
    val ctx = LocalContext.current
    val app = WristCordApp.of(ctx)
    val account = app.account
    val client = app.discord
    val scope = rememberCoroutineScope()

    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val snapshot by client.state.snapshot.collectAsStateWithLifecycle()
    val gateway by client.gateway.state.collectAsStateWithLifecycle()
    val gatewayVoice by Voice.gateway.collectAsStateWithLifecycle()
    val phone by StateStore.state.collectAsStateWithLifecycle()
    val error by StateStore.error.collectAsStateWithLifecycle()
    val pending by StateStore.pending.collectAsStateWithLifecycle()
    val assignments by SlotConfig.assignments.collectAsStateWithLifecycle()
    val artworkBitmap by ArtworkStore.bitmap.collectAsStateWithLifecycle()
    val artwork: ImageBitmap? = remember(artworkBitmap) { artworkBitmap?.asImageBitmap() }
    val updateStatus by updates.status.collectAsStateWithLifecycle()
    val voiceState = remember(gatewayVoice, phone, signedIn) { Voice.merge(gatewayVoice, phone, signedIn) }

    val root: Screen = if (signedIn) Screen.Home else Screen.Login
    val stack = remember(root) { mutableStateListOf(root).apply { if (signedIn) addAll(debugStack) } }
    val saveable = rememberSaveableStateHolder()
    fun push(s: Screen) = stack.add(s)
    fun pop() {
        if (stack.size > 1) {
            saveable.removeState(stack.last().key)
            stack.removeAt(stack.lastIndex)
        }
    }

    LaunchedEffect(destination) {
        when (destination) {
            Destination.REMOTE -> if (stack.last() !is Screen.Remote) push(Screen.Remote)
            Destination.OUTPUTS -> if (stack.last() !is Screen.Outputs) push(Screen.Outputs)
            Destination.NONE -> Unit
        }
        if (destination != Destination.NONE) onDestinationConsumed()
    }

    val onRemoteAction: (RemoteAction) -> Unit = { action ->
        when {
            action == RemoteAction.NONE -> Unit
            action.opensList -> push(Screen.Outputs)
            action == RemoteAction.REFRESH -> {
                Commands.refresh(ctx)
                account.touch("refresh")
            }
            action == RemoteAction.DISCONNECT && action.path != null ->
                Commands.fireOrElse(ctx, action.path!!) { client.leaveVoiceViaGateway() }
            action.path != null -> Commands.fire(ctx, action.path!!)
        }
    }

    @Composable
    fun Render(screen: Screen) {
        saveable.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Login -> LoginScreen(
                    onSignedIn = { token -> account.signedIn(token) },
                    onOpenSettings = { push(Screen.Settings) },
                )
                Screen.Home -> HomeScreen(
                    snapshot = snapshot,
                    gateway = gateway,
                    voice = gatewayVoice,
                    onOpenVoice = { push(Screen.Remote) },
                    onOpenDms = { push(Screen.Dms) },
                    onOpenGuild = { push(Screen.Guild(it)) },
                    onOpenStatus = { push(Screen.Status) },
                    onOpenSettings = { push(Screen.Settings) },
                )
                Screen.Dms -> DmListScreen(snapshot, onOpen = { push(Screen.Chat(it)) })
                is Screen.Guild -> GuildScreen(
                    snapshot, screen.id,
                    onOpenChannel = { push(Screen.Chat(it)) },
                    onOpenVoiceChannel = { id ->
                        if (gatewayVoice.inCall && gatewayVoice.channelId == id) push(Screen.Remote) else push(Screen.Participants(id))
                    },
                )
                is Screen.Chat -> ChatScreen(
                    snapshot, screen.channelId,
                    onOpenMessage = { push(Screen.Message(screen.channelId, it)) },
                    replyTo = screen.replyTo,
                    onReplyConsumed = {
                        val i = stack.indexOfLast { it is Screen.Chat && it.channelId == screen.channelId }
                        if (i >= 0 && screen.replyTo != null) stack[i] = Screen.Chat(screen.channelId)
                    },
                )
                is Screen.Message -> MessageActionsScreen(
                    snapshot, screen.channelId, screen.messageId,
                    onReply = {
                        pop()
                        val i = stack.indexOfLast { it is Screen.Chat && it.channelId == screen.channelId }
                        if (i >= 0) stack[i] = Screen.Chat(screen.channelId, replyTo = screen.messageId)
                    },
                    onDone = ::pop,
                )
                Screen.Remote -> RemoteHost(
                    state = voiceState,
                    error = error,
                    pending = pending,
                    assignments = assignments,
                    artwork = artwork,
                    context = if (signedIn && gatewayVoice.inCall) voiceLine(gatewayVoice.copy(selfMute = false, selfDeaf = false, serverMute = false, serverDeaf = false)) else null,
                    onAction = onRemoteAction,
                    onOpenSettings = { push(Screen.Buttons) },
                    onOpenParticipants = if (signedIn && gatewayVoice.inCall) { { push(Screen.Participants(gatewayVoice.channelId)) } } else null,
                )
                is Screen.Participants -> ParticipantsScreen(snapshot, screen.channelId)
                Screen.Outputs -> OutputsScreen(voiceState, error, pending, onChoose = { device ->
                    Commands.chooseOutput(ctx, device)
                    pop()
                })
                Screen.Settings -> SettingsScreen(
                    snapshot = snapshot,
                    signedIn = signedIn,
                    updateSummary = updates.summary(updateStatus),
                    ongoingEnabled = OngoingService.isEnabled(ctx),
                    onToggleOngoing = { OngoingService.setEnabled(ctx, it) },
                    onOpenStatus = { push(Screen.Status) },
                    onOpenTheme = { push(Screen.Theme) },
                    onOpenButtons = { push(Screen.Buttons) },
                    onOpenUpdates = {
                        updates.check()
                        push(Screen.Updates)
                    },
                    onSignOut = {
                        scope.launch { account.signOut(revoke = true) }
                    },
                )
                Screen.Status -> StatusScreen(snapshot, onDone = ::pop)
                Screen.Theme -> ThemeScreen()
                Screen.Buttons -> ButtonsScreen(
                    assignments = assignments,
                    onEditSlot = { push(Screen.SlotPicker(it)) },
                    onReset = { SlotConfig.reset(ctx) },
                )
                is Screen.SlotPicker -> SlotPickerScreen(
                    slot = screen.slot,
                    current = assignments[screen.slot] ?: screen.slot.default,
                    onChoose = { action ->
                        SlotConfig.assign(ctx, screen.slot, action)
                        pop()
                    },
                )
                Screen.Updates -> UpdateScreen(
                    status = updateStatus,
                    currentVersion = updates.currentVersionLabel,
                    canInstall = Updater.canRequestInstalls(ctx),
                    onCheck = { updates.check() },
                    onInstall = { updates.install(it) },
                )
            }
        }
    }

    WristCordTheme {
        AppScaffold(timeText = { if (stack.last() !is Screen.Remote) TimeText() }) {
            val current = stack.last()
            if (stack.size == 1) {
                Render(current)
            } else {
                SwipeToDismissBox(onDismissed = ::pop) { isBackground ->
                    Render(if (isBackground) stack[stack.lastIndex - 1] else current)
                }
            }
        }
    }
}
