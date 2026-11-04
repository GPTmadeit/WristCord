package com.wristcord.shared

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Switchable launcher icons.
 *
 * Android has no API for changing an app icon at runtime; the standard trick is to declare one
 * `<activity-alias>` per look, all pointing at the same activity, and enable exactly one.
 * Picked in the watch's Settings -> App icon.
 */
enum class IconVariant(
    val id: String,
    val label: String,
    val description: String,
) {
    /** Discord's blurple behind the Google-coloured mark. The default: homage, not imitation. */
    HOMAGE("homage", "Homage", "Discord blurple, Google colours"),

    /** The original: white ground, four-colour ring. */
    PIXEL("pixel", "Pixel", "White ground, Google colours"),

    /** Chat-bubble silhouette in the four colours, on blurple. */
    BUBBLE("bubble", "Bubble", "Discord-flavoured chat bubble"),

    /** Watch and phone, linked - the original two-device mark. */
    LINK("link", "Link", "Watch and phone, linked"),
    ;

    /** Alias component name. The leading package is fixed because applicationId is fixed. */
    fun component(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, "com.wristcord.Launcher" + id.replaceFirstChar(Char::uppercase))

    companion object {
        private const val TAG = "WristCord/Icon"
        private const val PREFS = "wristcord_icon"
        private const val KEY = "variant"

        val DEFAULT = HOMAGE

        fun byId(id: String?): IconVariant? = entries.firstOrNull { it.id == id }

        fun current(ctx: Context): IconVariant =
            byId(prefs(ctx).getString(KEY, null)) ?: DEFAULT

        /**
         * Enables [variant]'s alias and disables the rest.
         *
         * Order matters: the new alias is enabled first, because a moment with every alias
         * disabled is a moment where the launcher has no icon to show and some launchers drop
         * the shortcut permanently.
         */
        fun apply(ctx: Context, variant: IconVariant) {
            val pm = ctx.packageManager
            runCatching {
                pm.setComponentEnabledSetting(
                    variant.component(ctx),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                entries.filter { it != variant }.forEach { other ->
                    pm.setComponentEnabledSetting(
                        other.component(ctx),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
                prefs(ctx).edit().putString(KEY, variant.id).apply()
                Log.i(TAG, "icon set to ${variant.id}")
            }.onFailure { Log.w(TAG, "could not set icon to ${variant.id}", it) }
        }

        /** Re-asserts the stored choice; cheap, and covers a fresh install or a restore. */
        fun ensureApplied(ctx: Context) {
            val want = current(ctx)
            val pm = ctx.packageManager
            val state = runCatching { pm.getComponentEnabledSetting(want.component(ctx)) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val alreadyOn = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (want == DEFAULT && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (!alreadyOn) apply(ctx, want)
        }

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
