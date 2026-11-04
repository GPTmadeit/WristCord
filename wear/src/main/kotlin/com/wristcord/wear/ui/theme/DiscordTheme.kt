package com.wristcord.wear.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.wristcord.discord.proto.Theme
import com.wristcord.wear.discord.ThemePrefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Discord's four themes, keyed the way its token tables are. */
enum class DiscordTheme { DARK, ASH, ONYX, LIGHT }

fun Theme.tokens(): DiscordColors = when (this) {
    Theme.DARK -> DiscordColors.Dark
    Theme.ASH -> DiscordColors.Ash
    Theme.ONYX -> DiscordColors.Onyx
    Theme.LIGHT -> DiscordColors.Light
}

val LocalDiscordColors = staticCompositionLocalOf { DiscordColors.Onyx }
val LocalGradient = staticCompositionLocalOf<GradientPreset?> { null }

/** Discord's semantic colours for the current theme. */
val discord: DiscordColors
    @Composable get() = LocalDiscordColors.current

/**
 * Wear Material 3 components driven by Discord's palette, so stock buttons, cards and lists
 * come out in Discord's surfaces, text and blurple without each screen restyling them.
 */
fun DiscordColors.toColorScheme(): ColorScheme = ColorScheme(
    primary = backgroundBrand,
    primaryDim = backgroundBrand.copy(alpha = 0.82f),
    primaryContainer = backgroundBrand,
    onPrimary = controlPrimaryText,
    onPrimaryContainer = controlPrimaryText,
    secondary = statusOnline,
    secondaryDim = statusOnline.copy(alpha = 0.8f),
    secondaryContainer = surfaceHighest,
    onSecondary = Color.White,
    onSecondaryContainer = textDefault,
    tertiary = statusWarning,
    tertiaryDim = statusWarning.copy(alpha = 0.8f),
    tertiaryContainer = surfaceHighest,
    onTertiary = Color.Black,
    onTertiaryContainer = textDefault,
    surfaceContainerLow = baseLow,
    surfaceContainer = surfaceHigh,
    surfaceContainerHigh = surfaceHighest,
    onSurface = textDefault,
    onSurfaceVariant = textMuted,
    outline = borderStrong,
    outlineVariant = borderSubtle,
    background = baseLowest,
    onBackground = textDefault,
    error = statusDanger,
    errorDim = statusDanger.copy(alpha = 0.8f),
    errorContainer = controlCritical,
    onError = Color.White,
    onErrorContainer = controlCriticalText,
)

@Composable
fun WristCordTheme(content: @Composable () -> Unit) {
    val resolved by ThemePrefs.resolved.collectAsStateWithLifecycle()
    val gradient = remember(resolved.gradient) { resolved.gradient?.let { id -> GradientPresets.firstOrNull { it.protoId == id } } }
    // A Nitro gradient implies its own base theme, exactly as Discord applies it.
    val colors = when {
        gradient != null && gradient.base == DiscordTheme.LIGHT -> DiscordColors.Light
        gradient != null -> DiscordColors.Dark
        else -> resolved.theme.tokens()
    }
    CompositionLocalProvider(LocalDiscordColors provides colors, LocalGradient provides gradient) {
        MaterialTheme(colorScheme = remember(colors) { colors.toColorScheme() }, content = content)
    }
}

/** The screen ground: the theme's base, or the account's Nitro gradient. */
@Composable
fun ThemedBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = discord
    val gradient = LocalGradient.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (gradient == null) Modifier.background(colors.baseLowest)
                else Modifier.drawBehind {
                    drawRect(gradientBrush(gradient, size.width, size.height))
                    // Discord lays the base theme over the gradient at partial opacity so text
                    // stays readable; the watch does the same, a little stronger for the small type.
                    drawRect(colors.baseLowest.copy(alpha = 0.55f))
                }
            ),
    ) { content() }
}

/** CSS `linear-gradient(<angle>deg, ...)`: 0deg points up, angles run clockwise. */
fun gradientBrush(g: GradientPreset, width: Float, height: Float): Brush {
    val rad = Math.toRadians(g.angle.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val half = (abs(width * dx) + abs(height * dy)) / 2f
    val cx = width / 2f
    val cy = height / 2f
    return Brush.linearGradient(
        colorStops = g.stops.toTypedArray(),
        start = Offset(cx - dx * half, cy - dy * half),
        end = Offset(cx + dx * half, cy + dy * half),
    )
}
