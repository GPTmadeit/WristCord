package com.wristcord.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.wristcord.BuildConfig
import com.wristcord.mobile.link.IconSync
import com.wristcord.mobile.link.StatePublisher
import com.wristcord.mobile.link.StatusService
import com.wristcord.shared.Brand
import com.wristcord.shared.IconVariant
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater

class MainActivity : ComponentActivity() {

    private val updates: UpdateController by lazy {
        UpdateController(
            context = this,
            currentVersion = BuildConfig.VERSION_NAME,
            assetMarker = Updater.ASSET_PHONE,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IconVariant.ensureApplied(this)
        StatusService.startIfEnabled(this)
        setContent {
            WristCordTheme {
                OnboardingScreen(updates = updates)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the two settings screens is the most likely moment for the
        // permission picture - and therefore the watch's blocker text - to have changed.
        StatePublisher.get(this).refresh("phone app resumed")
        // Repairs a watch that was out of range when the icon last changed.
        IconSync.resend(this)
    }
}

/**
 * Deliberately not Material You: the phone and the watch are one product, and the watch has no
 * dynamic colour to follow. Both ends use Google's four product colours so a blue button means
 * mute in the same way on either screen.
 */
private val LightColors = lightColorScheme(
    primary = Color(Brand.BLUE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(Brand.GREEN),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC5EFCB),
    onSecondaryContainer = Color(0xFF00210C),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(Brand.RED),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410100),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF002E6E),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFA9D3B0),
    onSecondary = Color(0xFF10391B),
    secondaryContainer = Color(0xFF275030),
    onSecondaryContainer = Color(0xFFC5EFCB),
    tertiary = Color(0xFFEFC048),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFDF9B),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680003),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD5),
    surface = Color(0xFF121316),
    onSurface = Color(Brand.ON_SURFACE),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
)

@Composable
fun WristCordTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
