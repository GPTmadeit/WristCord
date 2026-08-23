package com.wristcord.mobile.discord

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.OutputDevice
import kotlinx.coroutines.delay

/**
 * Drives Discord's in-call UI by clicking it.
 *
 * If Discord is not already in front, we open it by firing the `contentIntent` of Discord's own
 * call notification (again: Discord's intent, not one we forged), wait for its window to show up
 * in [DiscordAccessibilityService.onAccessibilityEvent], do the click, then hand the phone back
 * with GLOBAL_ACTION_BACK so the user does not find Discord open on their lock screen later.
 */
class A11yStrategy(private val ctx: Context) {

    val name: String get() = "accessibility"

    suspend fun clickControl(slot: DiscordSelectors.Slot, human: String): Outcome =
        withDiscordInFront(human) { svc ->
            val pattern = DiscordSelectors.pattern(ctx, slot)
            val node = awaitNode(svc, pattern) ?: return@withDiscordInFront Outcome.Fail(
                name,
                "No \"$human\" control in Discord's voice panel. Saw: ${svc.clickableLabels().joinToString(", ").ifBlank { "nothing clickable" }}",
            )
            if (!svc.click(node)) {
                return@withDiscordInFront Outcome.Fail(name, "Discord refused the click on \"$human\"")
            }
            EventLog.i(TAG, "clicked $human")
            Outcome.Ok(name, "$human tapped in Discord")
        }

    /**
     * Opens Discord's own output chooser and picks [device] there.
     *
     * We deliberately do NOT call AudioManager.setCommunicationDevice() to do this: since
     * Android 12 that call only re-routes the *calling* app's communication audio - it is
     * scoped to WristCord's own uid, and Discord's voice stream is untouched by it. It would
     * look like it worked and change nothing. Discord's own control is the only thing that
     * moves Discord's audio, so we drive that.
     */
    suspend fun chooseOutput(device: OutputDevice): Outcome =
        withDiscordInFront("audio output") { svc ->
            val openPattern = DiscordSelectors.pattern(ctx, DiscordSelectors.Slot.A11Y_OUTPUT)
            val opener = awaitNode(svc, openPattern) ?: return@withDiscordInFront Outcome.Fail(
                name,
                "No audio-output control in Discord's voice panel. Saw: ${svc.clickableLabels().joinToString(", ").ifBlank { "nothing clickable" }}",
            )
            if (!svc.click(opener)) {
                return@withDiscordInFront Outcome.Fail(name, "Discord refused the click on its audio-output control")
            }

            delay(SHEET_SETTLE_MS)

            val target = deviceMatcher(device)
            val choice = awaitNode(svc, target) ?: return@withDiscordInFront Outcome.Fail(
                name,
                "\"${device.label}\" was not in Discord's output list. Saw: ${svc.clickableLabels().joinToString(", ").ifBlank { "nothing" }}",
            )
            if (!svc.click(choice)) {
                return@withDiscordInFront Outcome.Fail(name, "Discord refused the click on \"${device.label}\"")
            }
            EventLog.i(TAG, "selected output ${device.label}")
            Outcome.Ok(name, "Output switched to ${device.label}")
        }

    // ---------------------------------------------------------------------------------------

    private suspend fun withDiscordInFront(
        human: String,
        block: suspend (DiscordAccessibilityService) -> Outcome,
    ): Outcome {
        val svc = DiscordAccessibilityService.connected() ?: return Outcome.Fail(
            name,
            if (DiscordAccessibilityService.isGranted(ctx)) {
                "Accessibility service granted but not running - toggle it off and on"
            } else {
                "Accessibility service is off. Enable WristCord under Settings > Accessibility."
            },
        )

        if (!ensureScreenOn()) {
            return Outcome.Fail(
                name,
                "Phone screen is off - \"$human\" needs the screen. Mute and disconnect do not.",
            )
        }

        var broughtToFront = false
        if (!svc.isDiscordForeground()) {
            val voice = DiscordNotificationListener.voiceNotification(ctx)
            val open: PendingIntent = voice?.contentIntent ?: return Outcome.Fail(
                name,
                "Discord is not in front and its call notification has no contentIntent to open it",
            )
            try {
                open.send()
            } catch (e: PendingIntent.CanceledException) {
                return Outcome.Fail(name, "Discord cancelled its own contentIntent - rejoin the call")
            }
            if (!svc.awaitDiscordWindow(WINDOW_TIMEOUT_MS)) {
                return Outcome.Fail(name, "Discord did not come to the foreground within ${WINDOW_TIMEOUT_MS / 1000}s")
            }
            broughtToFront = true
            delay(PANEL_SETTLE_MS)
        }

        val outcome = try {
            block(svc)
        } finally {
            if (broughtToFront) {
                delay(BACK_DELAY_MS)
                runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            }
        }
        return outcome
    }

    /** Retries the lookup - Discord's panel animates in and the first frame has no controls. */
    private suspend fun awaitNode(svc: DiscordAccessibilityService, pattern: Regex) =
        (0 until NODE_ATTEMPTS).firstNotNullOfOrNull { attempt ->
            if (attempt > 0) delay(NODE_RETRY_MS)
            svc.findClickable(pattern)
        }

    /**
     * Best effort only. The deprecated wake-lock flags still bring the screen up on a lot of
     * devices, and where they do not we return false and say so out loud rather than clicking
     * into the dark.
     */
    private suspend fun ensureScreenOn(): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        if (pm.isInteractive) return true
        @Suppress("DEPRECATION")
        val lock = runCatching {
            pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WristCord:a11y",
            )
        }.getOrNull()
        runCatching { lock?.acquire(WAKE_HOLD_MS) }
        delay(WAKE_SETTLE_MS)
        val awake = pm.isInteractive
        runCatching { if (lock?.isHeld == true) lock.release() }
        return awake
    }

    /** Match on the device's own label first, then on generic words for its type. */
    private fun deviceMatcher(device: OutputDevice): Regex {
        val alternatives = buildList {
            add(Regex.escape(device.label))
            addAll(com.wristcord.mobile.audio.AudioOutputs.synonyms(device.type).map { Regex.escape(it) })
        }.distinct().joinToString("|")
        return Regex("($alternatives)", RegexOption.IGNORE_CASE)
    }

    companion object {
        private const val TAG = "WristCord/A11y"
        private const val WINDOW_TIMEOUT_MS = 5_000L
        private const val PANEL_SETTLE_MS = 500L
        private const val SHEET_SETTLE_MS = 600L
        private const val BACK_DELAY_MS = 350L
        private const val NODE_ATTEMPTS = 10
        private const val NODE_RETRY_MS = 200L
        private const val WAKE_HOLD_MS = 6_000L
        private const val WAKE_SETTLE_MS = 700L
    }
}
