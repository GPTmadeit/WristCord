package com.wristcord.wear.notify

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NotificationPrefs {
    enum class Mode(val label: String, val description: String) {
        FOLLOW("Match Discord", "Your Discord settings for each server and channel"),
        MENTIONS("DMs and mentions", "Never for ordinary server messages"),
        OFF("Off", "No connection in the background"),
    }

    private const val PREFS = "wristcord_notifications"
    private const val K_MODE = "mode"
    private const val K_ASKED = "permission_asked"
    private const val K_WATERMARK = "watermark"

    private val _mode = MutableStateFlow(Mode.FOLLOW)
    val mode: StateFlow<Mode> = _mode

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        _mode.value = Mode.entries.firstOrNull { it.name == prefs(ctx).getString(K_MODE, null) } ?: Mode.FOLLOW
    }

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit { putString(K_MODE, mode.name) }
        _mode.value = mode
    }

    fun shouldAskPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && _mode.value != Mode.OFF && !prefs(ctx).getBoolean(K_ASKED, false)

    fun markPermissionAsked(ctx: Context) = prefs(ctx).edit { putBoolean(K_ASKED, true) }

    /** Everything older than this snowflake has had its chance to notify. 0 when never set. */
    fun watermark(ctx: Context): Long = prefs(ctx).getLong(K_WATERMARK, 0L)

    fun setWatermark(ctx: Context, snowflake: Long) = prefs(ctx).edit { putLong(K_WATERMARK, snowflake) }
}
