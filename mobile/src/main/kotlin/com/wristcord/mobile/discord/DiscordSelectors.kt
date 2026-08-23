package com.wristcord.mobile.discord

import android.content.Context

/**
 * ==========================================================================================
 *  STEP 0 LANDING ZONE - the only file you should need to edit after running discovery.
 * ==========================================================================================
 *
 *  Discord's notification action titles and view ids are not documented and change between
 *  releases and locales, so nothing here is guessed silently: every pattern below is a
 *  DEFAULT that the Diagnostics screen can override at runtime (Settings are stored in
 *  SharedPreferences, so you can correct a selector on the phone without a rebuild).
 *
 *  Workflow:
 *    1. Join a Discord voice call on the phone.
 *    2. Open WristCord -> Diagnostics -> "Dump Discord notifications". That prints, for every
 *       com.discord notification: the channel, every action title, and whether each
 *       actionIntent is a broadcast / service / activity PendingIntent.
 *    3. Open Discord's voice panel, then WristCord -> Diagnostics -> "Dump voice panel tree".
 *       That prints viewIdResourceName + contentDescription for every clickable node.
 *    4. Paste the real titles/ids into the override fields (or edit the defaults here).
 *
 *  Patterns are Kotlin [Regex], matched case-insensitively with `containsMatchIn`.
 */
object DiscordSelectors {

    private const val PREFS = "wristcord_selectors"

    enum class Slot(
        val prefKey: String,
        val label: String,
        val help: String,
        val default: String,
    ) {
        /** Any mute-ish notification action. Discord flips the title, so match both directions. */
        NOTIF_MUTE(
            "notif_mute", "Notification: mute toggle",
            "Action title for the mute button on Discord's ongoing call notification.",
            "^(un)?mute",
        ),

        /**
         * Presence of an action matching this means we are CURRENTLY MUTED - the button offers
         * the way out of the state we are in. This is how mute state is derived; there is no
         * state API to read.
         */
        NOTIF_MUTED_NOW(
            "notif_muted_now", "Notification: title shown while muted",
            "If an action title matches this, WristCord reports 'muted'.",
            "^unmute",
        ),

        NOTIF_DEAFEN(
            "notif_deafen", "Notification: deafen toggle",
            "Only present on some Discord builds. When present, deafen works screen-off too.",
            "^(un)?deafen",
        ),

        NOTIF_DEAFENED_NOW(
            "notif_deafened_now", "Notification: title shown while deafened",
            "If an action title matches this, WristCord reports 'deafened'.",
            "^undeafen",
        ),

        NOTIF_DISCONNECT(
            "notif_disconnect", "Notification: leave call",
            "CallStyle notifications usually title this 'Hang Up'; older builds use 'Disconnect'.",
            "^(disconnect|hang ?up|leave|end call)",
        ),

        A11Y_DEAFEN(
            "a11y_deafen", "Voice panel: deafen control",
            "Matched against viewIdResourceName, contentDescription and text of clickable nodes.",
            "(un)?deafen",
        ),

        A11Y_MUTE(
            "a11y_mute", "Voice panel: mute control",
            "Fallback only. Used when Discord's notification has no mute action.",
            "(un)?mute",
        ),

        A11Y_DISCONNECT(
            "a11y_disconnect", "Voice panel: leave-call control",
            "Fallback only. Used when Discord's notification has no disconnect action.",
            "(disconnect|leave ?(voice|call)?|hang ?up|end call)",
        ),

        A11Y_OUTPUT(
            "a11y_output", "Voice panel: audio output control",
            "The speaker / audio-output button that opens Discord's own output chooser.",
            "(audio ?output|output device|speaker|switch audio|audio device|sound output)",
        ),
        ;

        val defaultRegex: Regex get() = Regex(default, RegexOption.IGNORE_CASE)
    }

    /** The pattern actually in force: a saved override if present, otherwise [Slot.default]. */
    fun pattern(ctx: Context, slot: Slot): Regex {
        val raw = override(ctx, slot)
        if (raw.isNullOrBlank()) return slot.defaultRegex
        return runCatching { Regex(raw, RegexOption.IGNORE_CASE) }.getOrElse { slot.defaultRegex }
    }

    fun override(ctx: Context, slot: Slot): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(slot.prefKey, null)

    fun setOverride(ctx: Context, slot: Slot, value: String?) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (value.isNullOrBlank()) remove(slot.prefKey) else putString(slot.prefKey, value)
        }.apply()
    }

    /** True when [raw] compiles; used to keep the Diagnostics text fields honest. */
    fun isValid(raw: String): Boolean =
        raw.isBlank() || runCatching { Regex(raw) }.isSuccess
}
