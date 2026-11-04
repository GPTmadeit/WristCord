package com.wristcord.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wristcord.discord.Cdn
import com.wristcord.discord.remoteauth.RemoteAuth
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.QrCode
import com.wristcord.wear.ui.theme.ThemedBackground
import com.wristcord.wear.ui.theme.discord
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Signing in is Discord's own "scan to log in": the watch shows a QR, Discord on the phone
 * (You -> gear -> Scan QR Code) reads it, the phone asks its owner to confirm, and the watch
 * receives a session of its own. No password is typed on a watch.
 */
@Composable
fun LoginScreen(onSignedIn: (String) -> Unit, onOpenSettings: () -> Unit) {
    val app = WristCordApp.of(LocalContext.current)
    val auth = remember { app.discord.newRemoteAuth() }
    val step by auth.step.collectAsStateWithLifecycle()

    DisposableEffect(auth) {
        auth.start()
        onDispose { auth.stop() }
    }
    // The very first attempt often races the watch's network coming up; try twice more on our
    // own before asking the user to.
    var retries by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        if (step is RemoteAuth.Step.Failed && retries < 2) {
            delay(2_000)
            retries++
            auth.start()
        }
    }
    (step as? RemoteAuth.Step.Success)?.let { s ->
        LaunchedEffect(s.token) { onSignedIn(s.token) }
    }

    val c = discord
    ThemedBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val s = step) {
                is RemoteAuth.Step.Connecting, is RemoteAuth.Step.LoggingIn, is RemoteAuth.Step.Success ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Caption(if (s is RemoteAuth.Step.Connecting) "Connecting to Discord" else "Signing in")
                    }

                is RemoteAuth.Step.ShowQr -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 18.dp, start = 24.dp, end = 24.dp),
                ) {
                    Text(
                        "Scan with Discord",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.headingPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    QrCode(s.qrUrl, size = 104.dp)
                    Caption("You  ›  ⚙  ›  Scan QR Code")
                    FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings", modifier = Modifier.size(16.dp))
                    }
                }

                is RemoteAuth.Step.Scanned -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 26.dp),
                ) {
                    Avatar(
                        url = Cdn.avatar(s.user.id, s.user.avatar, 128),
                        name = s.user.username,
                        size = 56.dp,
                    )
                    Text(
                        s.user.username,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.headingPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Caption("Confirm the login on your phone")
                }

                is RemoteAuth.Step.Failed, is RemoteAuth.Step.Cancelled -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 28.dp),
                ) {
                    Text(
                        if (s is RemoteAuth.Step.Failed) "Couldn't sign in" else "Cancelled on your phone",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.headingPrimary,
                        textAlign = TextAlign.Center,
                    )
                    if (s is RemoteAuth.Step.Failed) Caption(s.message, color = c.textCritical)
                    Button(
                        onClick = { auth.start() },
                        colors = ButtonDefaults.buttonColors(),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text("New code") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
