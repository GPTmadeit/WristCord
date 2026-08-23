package com.wristcord.mobile.link

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wristcord.mobile.audio.AudioOutputs
import com.wristcord.mobile.discord.Discord
import com.wristcord.mobile.discord.DiscordAccessibilityService
import com.wristcord.mobile.discord.DiscordNotificationListener
import com.wristcord.mobile.discord.DiscordSelectors
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Derives the current voice state from Discord's notification and republishes it to the watch.
 *
 * There is no state to read anywhere: Discord exposes mute/deafen only as the *label on the
 * button that would undo it*. An action titled "Unmute" means we are muted right now. That is
 * the whole inference, and it is why the tri-state [Toggle] exists - when the call notification
 * carries neither label we report UNKNOWN instead of inventing "off".
 */
class StatePublisher private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    fun refresh(reason: String) {
        scope.launch { publish(reason) }
    }

    /** For use right after an action: Discord's repost may land either side of our read. */
    fun refreshSoon(reason: String) {
        scope.launch {
            delay(400)
            publish("$reason (+400ms)")
            delay(900)
            publish("$reason (+1.3s)")
        }
    }

    suspend fun publish(reason: String) = gate.withLock {
        val next = compute()
        val previous = _state.value
        _state.value = next
        if (previous.copy(updatedAt = 0) == next.copy(updatedAt = 0)) return@withLock

        val request = PutDataMapRequest.create(Wire.PATH_STATE).apply {
            dataMap.putAll(next.toDataMap())
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(app).putDataItem(request).await()
            EventLog.i(TAG, "published state ($reason): ${describe(next)}")
        } catch (t: Throwable) {
            EventLog.e(TAG, "could not publish state: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun compute(): VoiceState {
        val blocker = when {
            !Discord.isInstalled(app) -> "Discord is not installed on the phone"
            !DiscordNotificationListener.isGranted(app) -> "Phone: notification access is off"
            DiscordNotificationListener.connected() == null -> "Phone: notification listener not connected"
            !DiscordAccessibilityService.isGranted(app) -> "Phone: accessibility off (deafen/output unavailable)"
            else -> ""
        }

        val voice = DiscordNotificationListener.voiceNotification(app)
        val mutedNow = DiscordSelectors.pattern(app, DiscordSelectors.Slot.NOTIF_MUTED_NOW)
        val muteAny = DiscordSelectors.pattern(app, DiscordSelectors.Slot.NOTIF_MUTE)
        val deafenedNow = DiscordSelectors.pattern(app, DiscordSelectors.Slot.NOTIF_DEAFENED_NOW)
        val deafenAny = DiscordSelectors.pattern(app, DiscordSelectors.Slot.NOTIF_DEAFEN)

        return VoiceState(
            inCall = voice != null,
            channel = voice?.let { it.text.ifBlank { it.title } }.orEmpty(),
            // Order matters: "Unmute" also matches the generic mute pattern.
            muted = toggleFrom(voice?.hasAction(mutedNow), voice?.hasAction(muteAny)),
            deafened = toggleFrom(voice?.hasAction(deafenedNow), voice?.hasAction(deafenAny)),
            outputs = AudioOutputs.list(app),
            blocker = blocker,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun toggleFrom(undoLabelPresent: Boolean?, anyLabelPresent: Boolean?): Toggle = when {
        undoLabelPresent == true -> Toggle.ON
        anyLabelPresent == true -> Toggle.OFF
        else -> Toggle.UNKNOWN
    }

    private fun describe(s: VoiceState) =
        "inCall=${s.inCall} muted=${s.muted} deafened=${s.deafened} outputs=${s.outputs.size}" +
            if (s.blocker.isBlank()) "" else " blocker=\"${s.blocker}\""

    companion object {
        private const val TAG = "WristCord/State"

        @Volatile
        private var instance: StatePublisher? = null

        fun get(ctx: Context): StatePublisher =
            instance ?: synchronized(this) {
                instance ?: StatePublisher(ctx.applicationContext).also { instance = it }
            }
    }
}
