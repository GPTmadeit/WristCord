package com.wristcord.mobile.discord

import android.content.Context
import android.content.pm.PackageManager

/**
 * WristCord never talks to Discord's servers. It only:
 *   1. fires PendingIntents that Discord itself published on its own notification, and
 *   2. clicks Discord's own on-screen controls through an AccessibilityService.
 *
 * Both are the same things a human with the phone in their hand can do, which is the only
 * way to remote-control the Discord Android client without a user token, a self-bot or a
 * reimplemented client - all of which are Terms of Service violations that get accounts
 * terminated. There is no public API for controlling the Discord Android app.
 */
object Discord {
    const val PACKAGE = "com.discord"

    /** Discord ships alternate channels under the same prefix; accept all of them. */
    fun isDiscord(pkg: CharSequence?): Boolean =
        pkg != null && pkg.toString().let { it == PACKAGE || it.startsWith("$PACKAGE.") }

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** Uniform result type for both strategies. Nothing in this app returns Unit on failure. */
sealed interface Outcome {
    val strategy: String
    val message: String

    data class Ok(override val strategy: String, override val message: String) : Outcome
    data class Fail(override val strategy: String, override val message: String) : Outcome

    val ok: Boolean get() = this is Ok
}
