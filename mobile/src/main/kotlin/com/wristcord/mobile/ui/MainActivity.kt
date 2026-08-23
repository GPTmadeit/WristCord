package com.wristcord.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wristcord.mobile.link.StatePublisher

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WristCordTheme {
                OnboardingScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the two settings screens is the most likely moment for the
        // permission picture - and therefore the watch's blocker text - to have changed.
        StatePublisher.get(this).refresh("phone app resumed")
    }
}

@Composable
fun WristCordTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // minSdk is 31, so Material You dynamic colour is always available here.
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
