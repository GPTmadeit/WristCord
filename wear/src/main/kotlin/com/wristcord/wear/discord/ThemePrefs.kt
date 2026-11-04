package com.wristcord.wear.discord

import android.content.Context
import com.wristcord.discord.proto.Theme
import com.wristcord.discord.proto.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which of Discord's themes the watch wears. By default it follows the account's own
 * appearance setting - change Discord to Onyx on your phone and the watch follows a moment
 * later - with an override for anyone who wants the watch to differ.
 */
object ThemePrefs {

    enum class Choice(val label: String) {
        FOLLOW("Match Discord"), DARK("Dark"), ASH("Ash"), ONYX("Onyx"), LIGHT("Light");

        fun theme(account: Theme): Theme = when (this) {
            FOLLOW -> account
            DARK -> Theme.DARK
            ASH -> Theme.ASH
            ONYX -> Theme.ONYX
            LIGHT -> Theme.LIGHT
        }
    }

    data class Resolved(val theme: Theme, val gradient: Int?, val choice: Choice, val gradients: Boolean)

    private const val PREFS = "wristcord_theme"
    private const val K_CHOICE = "choice"
    private const val K_GRADIENTS = "gradients"
    private const val K_ACCOUNT_THEME = "account_theme"
    private const val K_ACCOUNT_GRADIENT = "account_gradient"

    private val _resolved = MutableStateFlow(Resolved(Theme.ONYX, null, Choice.FOLLOW, true))
    val resolved: StateFlow<Resolved> = _resolved

    fun load(ctx: Context) {
        _resolved.value = read(ctx)
    }

    fun read(ctx: Context): Resolved {
        val p = prefs(ctx)
        val choice = runCatching { Choice.valueOf(p.getString(K_CHOICE, Choice.FOLLOW.name)!!) }.getOrDefault(Choice.FOLLOW)
        // Until the account has told us, Onyx: true black is what an OLED watch wants anyway.
        val account = runCatching { Theme.valueOf(p.getString(K_ACCOUNT_THEME, Theme.ONYX.name)!!) }.getOrDefault(Theme.ONYX)
        val gradients = p.getBoolean(K_GRADIENTS, true)
        val accountGradient = p.getInt(K_ACCOUNT_GRADIENT, -1).takeIf { it >= 0 }
        val gradient = if (choice == Choice.FOLLOW && gradients) accountGradient else null
        return Resolved(choice.theme(account), gradient, choice, gradients)
    }

    /** The theme Discord itself is set to on this account, whatever the watch override. */
    fun accountTheme(ctx: Context): Theme =
        runCatching { Theme.valueOf(prefs(ctx).getString(K_ACCOUNT_THEME, Theme.ONYX.name)!!) }.getOrDefault(Theme.ONYX)

    fun accountGradient(ctx: Context): Int? = prefs(ctx).getInt(K_ACCOUNT_GRADIENT, -1).takeIf { it >= 0 }

    fun setChoice(ctx: Context, choice: Choice) {
        prefs(ctx).edit().putString(K_CHOICE, choice.name).apply()
        load(ctx)
    }

    fun setGradients(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_GRADIENTS, on).apply()
        load(ctx)
    }

    fun rememberAccountTheme(ctx: Context, settings: UserSettings) {
        prefs(ctx).edit()
            .putString(K_ACCOUNT_THEME, settings.theme.name)
            .putInt(K_ACCOUNT_GRADIENT, settings.gradientPreset ?: -1)
            .apply()
        load(ctx)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
