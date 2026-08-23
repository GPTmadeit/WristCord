package com.wristcord.mobile.discord

import android.app.PendingIntent
import android.content.Context
import com.wristcord.mobile.util.EventLog

/**
 * Fires the PendingIntent that Discord itself attached to an action on its own ongoing voice
 * notification. Discord built the intent, Discord owns the receiver - we only pull the trigger.
 *
 * This is the path that works with the phone screen off, because notification actions are
 * overwhelmingly broadcast or service PendingIntents (the Diagnostics dump tells you which
 * kind each one is). Nothing here needs Discord to be foregrounded, unlocked, or visible.
 */
class NotificationActionStrategy(private val ctx: Context) {

    val name: String get() = "notification"

    fun fire(slot: DiscordSelectors.Slot, human: String): Outcome {
        if (DiscordNotificationListener.connected() == null) {
            return Outcome.Fail(
                name,
                if (DiscordNotificationListener.isGranted(ctx)) {
                    "Notification access granted but not connected yet - toggle it off and on"
                } else {
                    "Notification access is off. Enable WristCord under Notification access."
                },
            )
        }

        val voice = DiscordNotificationListener.voiceNotification(ctx)
            ?: return Outcome.Fail(name, "No Discord call notification - are you in a voice channel?")

        val pattern = DiscordSelectors.pattern(ctx, slot)
        val action = voice.findAction(pattern) ?: return Outcome.Fail(
            name,
            "No \"$human\" action on Discord's notification (titles: ${titles(voice)})",
        )

        val intent = action.actionIntent
            ?: return Outcome.Fail(name, "\"${action.title}\" has no actionIntent")

        return try {
            intent.send()
            val kind = describe(intent)
            EventLog.i(TAG, "fired \"${action.title}\" ($kind)")
            Outcome.Ok(name, "$human via \"${action.title}\" ($kind)")
        } catch (e: PendingIntent.CanceledException) {
            Outcome.Fail(name, "Discord cancelled the \"${action.title}\" intent - rejoin the call")
        } catch (t: Throwable) {
            Outcome.Fail(name, "Could not fire \"${action.title}\": ${t.javaClass.simpleName}")
        }
    }

    /** True when Discord is currently offering this action, i.e. the fast path is available. */
    fun canHandle(slot: DiscordSelectors.Slot): Boolean {
        val voice = DiscordNotificationListener.voiceNotification(ctx) ?: return false
        return voice.hasAction(DiscordSelectors.pattern(ctx, slot))
    }

    private fun titles(voice: VoiceNotification): String =
        voice.actions.joinToString(", ") { "\"${it.title}\"" }.ifBlank { "none" }

    companion object {
        private const val TAG = "WristCord/Notif"

        /**
         * PendingIntent.isBroadcast/isService/isActivity are public API since API 31, which is
         * exactly our minSdk. This is what the Step 0 dump reports per action.
         */
        fun describe(pi: PendingIntent): String = when {
            pi.isBroadcast -> "broadcast"
            pi.isForegroundService -> "foreground service"
            pi.isService -> "service"
            pi.isActivity -> "activity"
            else -> "unknown"
        }
    }
}
