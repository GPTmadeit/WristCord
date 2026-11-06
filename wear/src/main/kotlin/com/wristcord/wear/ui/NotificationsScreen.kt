package com.wristcord.wear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import com.wristcord.wear.notify.NotificationPrefs
import com.wristcord.wear.notify.NotificationService
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.radioColors
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.theme.discord

@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    val c = discord
    val mode by NotificationPrefs.mode.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        NotificationService.sync(ctx)
    }
    LifecycleResumeEffect(Unit) {
        allowed = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Notifications") } }
        if (!allowed && mode != NotificationPrefs.Mode.OFF) {
            row(spec, "blocked") { Caption("Notifications are blocked for WristCord on this watch.", modifier, color = c.textCritical) }
            row(spec, "allow") {
                Button(
                    onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Allow", maxLines = 1) },
                )
            }
        }
        for (choice in NotificationPrefs.Mode.entries) row(spec, choice.name) {
            RadioButton(
                selected = mode == choice,
                onSelect = {
                    NotificationPrefs.setMode(ctx, choice)
                    NotificationService.sync(ctx)
                    if (choice == NotificationPrefs.Mode.OFF) com.wristcord.wear.WristCordApp.of(ctx).notifier.clearAll()
                },
                modifier = modifier,
                transformation = transformation,
                colors = radioColors(),
                label = { Text(choice.label, maxLines = 1) },
                secondaryLabel = { Text(choice.description, maxLines = 2) },
            )
        }
        row(spec, "battery") {
            Caption("To notify you with the app closed, the watch stays connected to Discord, which uses more battery. Off stops that.", modifier)
        }
        row(spec, "duplicates") {
            Caption("Seeing each message twice? Your phone's Discord is mirroring too. Turn it off for Discord in your phone's watch app.", modifier)
        }
    }
}
