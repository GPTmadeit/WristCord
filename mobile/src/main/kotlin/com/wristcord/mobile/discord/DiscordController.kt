package com.wristcord.mobile.discord

import android.content.Context
import com.wristcord.mobile.link.StatePublisher
import com.wristcord.mobile.util.EventLog
import com.wristcord.mobile.util.Toasts
import com.wristcord.shared.ActionResult
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.Wire

/**
 * Picks a strategy per action and guarantees an [ActionResult] for every request.
 *
 * Strategy policy:
 *   mute / disconnect -> notification actions (screen-off capable), accessibility as fallback
 *                        only when Discord's notification does not carry that action.
 *   deafen            -> accessibility, unless Step 0 discovery found a real deafen action on
 *                        the notification, in which case the screen-off path wins.
 *   output            -> accessibility, always: only Discord's own control moves Discord's audio.
 */
class DiscordController(private val ctx: Context) {

    private val notifications = NotificationActionStrategy(ctx)
    private val a11y = A11yStrategy(ctx)

    suspend fun handle(path: String, payload: ByteArray): ActionResult {
        val outcome = when (path) {
            Wire.PATH_MUTE -> preferNotification(
                DiscordSelectors.Slot.NOTIF_MUTE, DiscordSelectors.Slot.A11Y_MUTE, "Mute",
            )

            Wire.PATH_DISCONNECT -> preferNotification(
                DiscordSelectors.Slot.NOTIF_DISCONNECT, DiscordSelectors.Slot.A11Y_DISCONNECT, "Disconnect",
            )

            Wire.PATH_DEAFEN -> preferNotification(
                DiscordSelectors.Slot.NOTIF_DEAFEN, DiscordSelectors.Slot.A11Y_DEAFEN, "Deafen",
            )

            Wire.PATH_OUTPUT -> handleOutput(payload)

            Wire.PATH_REFRESH -> {
                StatePublisher.get(ctx).refresh("watch asked")
                Outcome.Ok("state", "State refreshed")
            }

            else -> Outcome.Fail("router", "Unknown command $path")
        }

        if (path != Wire.PATH_REFRESH) {
            // Discord re-posts its notification when mute/deafen flips; catch it twice because
            // the repost can land either side of our own read.
            StatePublisher.get(ctx).refreshSoon("after $path")
        }

        if (!outcome.ok) {
            EventLog.e(TAG, "${path}: ${outcome.message}")
            Toasts.show(ctx, "WristCord: ${outcome.message}")
        } else {
            EventLog.i(TAG, "${path}: ${outcome.message}")
        }

        return ActionResult(
            path = path,
            ok = outcome.ok,
            message = outcome.message,
            strategy = outcome.strategy,
        )
    }

    private suspend fun preferNotification(
        notifSlot: DiscordSelectors.Slot,
        a11ySlot: DiscordSelectors.Slot,
        human: String,
    ): Outcome {
        if (notifications.canHandle(notifSlot)) {
            val viaNotification = notifications.fire(notifSlot, human)
            if (viaNotification.ok) return viaNotification
            EventLog.w(TAG, "notification path failed for $human: ${viaNotification.message}")
            val viaA11y = a11y.clickControl(a11ySlot, human)
            return if (viaA11y.ok) viaA11y else viaNotification
        }
        // No such action on the notification at all. Say so, then try the UI.
        val viaA11y = a11y.clickControl(a11ySlot, human)
        return when (viaA11y) {
            is Outcome.Ok -> Outcome.Ok(viaA11y.strategy, "${viaA11y.message} (no notification action for $human)")
            is Outcome.Fail -> Outcome.Fail(
                viaA11y.strategy,
                "$human unavailable: no notification action, and ${viaA11y.message.replaceFirstChar { it.lowercase() }}",
            )
        }
    }

    private suspend fun handleOutput(payload: ByteArray): Outcome {
        val encoded = payload.toString(Charsets.UTF_8)
        val device = OutputDevice.decode(encoded)
            ?: return Outcome.Fail("router", "Watch sent an unreadable output device")
        return a11y.chooseOutput(device)
    }

    private companion object {
        const val TAG = "WristCord/Ctl"
    }
}
