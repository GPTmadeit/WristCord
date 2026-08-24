package com.wristcord.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.wristcord.shared.Brand
import com.wristcord.shared.RemoteAction

/**
 * Pixel palette on true black.
 *
 * Black rather than dark grey because the Pixel Watch panel is OLED: unlit pixels cost nothing,
 * the bezel disappears into the screen, and the four product colours sit on it the way they do
 * on Google's own watch surfaces.
 */
private val WristCordColors = ColorScheme(
    primary = Color(Brand.BLUE),
    primaryDim = Color(Brand.BLUE_DIM),
    primaryContainer = Color(Brand.BLUE_DIM),
    onPrimary = Color.White,
    onPrimaryContainer = Color(Brand.ON_SURFACE),

    secondary = Color(Brand.GREEN),
    secondaryDim = Color(Brand.GREEN_DIM),
    secondaryContainer = Color(Brand.GREEN_DIM),
    onSecondary = Color.White,
    onSecondaryContainer = Color(Brand.ON_SURFACE),

    tertiary = Color(Brand.YELLOW),
    tertiaryDim = Color(Brand.YELLOW_DIM),
    tertiaryContainer = Color(Brand.YELLOW_DIM),
    onTertiary = Color(Brand.INK),
    onTertiaryContainer = Color(Brand.ON_SURFACE),

    surfaceContainerLow = Color(0xFF16171A),
    surfaceContainer = Color(Brand.SURFACE),
    surfaceContainerHigh = Color(Brand.SURFACE_HIGH),
    onSurface = Color(Brand.ON_SURFACE),
    onSurfaceVariant = Color(Brand.ON_SURFACE_VARIANT),

    outline = Color(Brand.OUTLINE),
    outlineVariant = Color(0xFF3C4043),

    background = Color.Black,
    onBackground = Color(Brand.ON_SURFACE),

    error = Color(Brand.RED),
    errorDim = Color(Brand.RED_DIM),
    errorContainer = Color(Brand.RED_DIM),
    onError = Color.White,
    onErrorContainer = Color(Brand.ON_SURFACE),
)

@Composable
fun WristCordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WristCordColors, content = content)
}

/** Each action owns one Google colour, everywhere it appears. */
val RemoteAction.color: Color get() = Color(accent)

val RemoteAction.dimColor: Color
    get() = when (this) {
        RemoteAction.MUTE -> Color(Brand.BLUE_DIM)
        RemoteAction.DEAFEN -> Color(Brand.YELLOW_DIM)
        RemoteAction.OUTPUT -> Color(Brand.GREEN_DIM)
        RemoteAction.DISCONNECT -> Color(Brand.RED_DIM)
        RemoteAction.REFRESH, RemoteAction.NONE -> Color(Brand.SURFACE_HIGH)
    }

/** Text/icon colour that stays readable on top of [color] when the button is filled. */
val RemoteAction.onColor: Color
    get() = when (this) {
        RemoteAction.DEAFEN -> Color(Brand.INK) // yellow needs dark ink
        else -> Color.White
    }
