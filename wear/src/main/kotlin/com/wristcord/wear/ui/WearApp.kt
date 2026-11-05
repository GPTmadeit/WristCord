package com.wristcord.wear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.wristcord.wear.ui.theme.WristCordTheme
import com.wristcord.wear.voice.CallState
import com.wristcord.wear.voice.Headset
import com.wristcord.wear.voice.JoinBlocker
import com.wristcord.wear.voice.SlotConfig
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface Screen {
    val key: String
    data object Home : Screen { override val key = "home" }
    data object Login : Screen { override val key = "login" }
    data object Dms : Screen { override val key = "dms" }
    data class Guild(val id: Long) : Screen { override val key = "guild-$id" }
    data class Chat(val channelId: Long, val replyTo: Long? = null) : Screen { override val key = "chat-$channelId" }
    data class Message(val channelId: Long, val messageId: Long) : Screen { override val key = "msg-$messageId" }
    data class ImageSources(val channelId: Long) : Screen { override val key = "image-sources-$channelId" }
    data class Gallery(val channelId: Long) : Screen { override val key = "gallery-$channelId" }
    data class Doodle(val channelId: Long) : Screen { override val key = "doodle-$channelId" }
    data class PhoneDrop(val channelId: Long) : Screen { override val key = "phone-drop-$channelId" }
    data class ImagePreview(val channelId: Long, val path: String) : Screen { override val key = "image-preview-$path" }
    data object Remote : Screen { override val key = "remote" }
    data class VoiceChannel(val channelId: Long) : Screen { override val key = "voice-$channelId" }
    data object Outputs : Screen { override val key = "outputs" }
    data object Settings : Screen { override val key = "settings" }
    data object Status : Screen { override val key = "status" }
    data object Theme : Screen { override val key = "theme" }
    data object Icons : Screen { override val key = "icons" }
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
    val voiceCall = app.voiceCall
    val scope = rememberCoroutineScope()

    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val snapshot by client.state.snapshot.collectAsStateWithLifecycle()
    val gateway by client.gateway.state.collectAsStateWithLifecycle()
    val gatewayVoice by Voice.gateway.collectAsStateWithLifecycle()
    val call by voiceCall.state.collectAsStateWithLifecycle()
    val speaking by voiceCall.speaking.collectAsStateWithLifecycle()
    val headsets by remember { Headset.observe(ctx).map { Headset.available(ctx) } }
        .collectAsStateWithLifecycle(initialValue = remember { Headset.available(ctx) })
    val assignments by SlotConfig.assignments.collectAsStateWithLifecycle()
    val updateStatus by updates.status.collectAsStateWithLifecycle()
    val voiceState = remember(gatewayVoice, call, signedIn, headsets, snapshot.channels) {
        Voice.merge(
            gateway = gatewayVoice,
            call = call,
            signedIn = signedIn,
            headsets = headsets,
            routed = (call as? CallState.Live)?.headset,
            channelName = { id -> snapshot.channels[id]?.let { snapshot.channelTitle(it) } },
        )
    }

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

    // A finished drawing or phone upload takes its screen's place, so backing out of the preview
    // lands on the source list instead of restarting that screen.
    fun replaceTop(s: Screen) {
        saveable.removeState(stack.last().key)
        stack[stack.lastIndex] = s
    }
    fun backToChat(channelId: Long) {
        while (stack.size > 1 && (stack.last() as? Screen.Chat)?.channelId != channelId) pop()
    }

    LaunchedEffect(destination) {
        when (destination) {
            Destination.REMOTE -> if (stack.last() !is Screen.Remote) push(Screen.Remote)
            Destination.OUTPUTS -> if (stack.last() !is Screen.Outputs) push(Screen.Outputs)
            Destination.NONE -> Unit
        }
        if (destination != Destination.NONE) onDestinationConsumed()
    }

    // "Move here": take the call that is on another device, after the mic permission if needed.
    val takeOver: () -> Unit = {
        if (gatewayVoice.inCall && gatewayVoice.channelId != 0L) {
            voiceCall.join(gatewayVoice.guildId.takeIf { it != 0L }, gatewayVoice.channelId)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takeOver()
    }

    val onRemoteAction: (RemoteAction) -> Unit = { action ->
        val onWatch = voiceCall.isOnWatch
        when (action) {
            RemoteAction.NONE -> Unit
            RemoteAction.OUTPUT -> push(Screen.Outputs)
            RemoteAction.MUTE -> if (onWatch) voiceCall.toggleMute()
            RemoteAction.DEAFEN -> if (onWatch) voiceCall.toggleDeafen()
            // Off the watch, leaving goes through the gateway: best effort for a call on
            // another device, since Discord's own apps never do that.
            RemoteAction.DISCONNECT -> if (onWatch) voiceCall.leave() else client.leaveVoiceViaGateway()
            RemoteAction.REFRESH -> {
                account.touch("refresh")
                client.gateway.retryNow()
            }
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
                    onOpenVoiceChannel = { push(Screen.VoiceChannel(it)) },
                )
                is Screen.Chat -> ChatScreen(
                    snapshot, screen.channelId,
                    onOpenMessage = { push(Screen.Message(screen.channelId, it)) },
                    onSendImage = { push(Screen.ImageSources(screen.channelId)) },
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
                is Screen.ImageSources -> ImageSourcesScreen(
                    onGallery = { push(Screen.Gallery(screen.channelId)) },
                    onDraw = { push(Screen.Doodle(screen.channelId)) },
                    onPhone = { push(Screen.PhoneDrop(screen.channelId)) },
                )
                is Screen.Gallery -> GalleryScreen(onPicked = { push(Screen.ImagePreview(screen.channelId, it.path)) })
                is Screen.Doodle -> DoodleScreen(onDone = { replaceTop(Screen.ImagePreview(screen.channelId, it.path)) }, onBack = ::pop)
                is Screen.PhoneDrop -> PhoneDropScreen(onReceived = { replaceTop(Screen.ImagePreview(screen.channelId, it.path)) })
                is Screen.ImagePreview -> ImagePreviewScreen(
                    channelId = screen.channelId,
                    file = java.io.File(screen.path),
                    onSent = { backToChat(screen.channelId) },
                )
                Screen.Remote -> RemoteHost(
                    state = voiceState,
                    error = null,
                    pending = null,
                    assignments = assignments,
                    artwork = null,
                    // The watch's own call is "on this watch" even before Discord's echo of it arrives.
                    context = if (signedIn && voiceState.inCall) voiceLine(
                        gatewayVoice.copy(
                            device = if (voiceState.onWatch) "watch" else gatewayVoice.device,
                            selfMute = false, selfDeaf = false, serverMute = false, serverDeaf = false,
                        )
                    ).ifBlank { null } else null,
                    onAction = onRemoteAction,
                    onOpenSettings = { push(Screen.Buttons) },
                    onOpenParticipants = (voiceCall.currentChannel() ?: gatewayVoice.channelId.takeIf { gatewayVoice.inCall })?.let { id -> { push(Screen.VoiceChannel(id)) } },
                    onTakeOver = {
                        if (voiceCall.blocker(gatewayVoice.channelId) == JoinBlocker.NO_MIC_PERMISSION) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        else takeOver()
                    },
                )
                is Screen.VoiceChannel -> VoiceChannelScreen(
                    snapshot, screen.channelId, call, speaking,
                    onOpenRemote = {
                        pop()
                        push(Screen.Remote)
                    },
                )
                Screen.Outputs -> OutputsScreen(voiceState, onChoose = { device ->
                    Headset.available(ctx).firstOrNull { it.id == device.id }?.let { voiceCall.route(it) }
                    pop()
                })
                Screen.Settings -> SettingsScreen(
                    snapshot = snapshot,
                    signedIn = signedIn,
                    updateSummary = updates.summary(updateStatus),
                    onOpenStatus = { push(Screen.Status) },
                    onOpenTheme = { push(Screen.Theme) },
                    onOpenIcons = { push(Screen.Icons) },
                    onOpenButtons = { push(Screen.Buttons) },
                    onOpenUpdates = {
                        updates.check()
                        push(Screen.Updates)
                    },
                    onSignOut = {
                        voiceCall.leave()
                        scope.launch { account.signOut(revoke = true) }
                    },
                )
                Screen.Status -> StatusScreen(snapshot, onDone = ::pop)
                Screen.Theme -> ThemeScreen()
                Screen.Icons -> IconScreen(onDone = ::pop)
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
