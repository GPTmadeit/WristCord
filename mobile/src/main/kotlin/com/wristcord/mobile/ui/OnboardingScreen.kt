package com.wristcord.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.wristcord.mobile.diagnostics.Probe
import com.wristcord.mobile.discord.Discord
import com.wristcord.mobile.discord.DiscordAccessibilityService
import com.wristcord.mobile.discord.DiscordNotificationListener
import com.wristcord.mobile.discord.DiscordSelectors
import com.wristcord.mobile.link.StatePublisher
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import kotlinx.coroutines.delay
import java.io.File

private data class Grants(
    val discordInstalled: Boolean,
    val notificationGranted: Boolean,
    val notificationConnected: Boolean,
    val accessibilityGranted: Boolean,
    val accessibilityConnected: Boolean,
) {
    companion object {
        fun read(ctx: Context) = Grants(
            discordInstalled = Discord.isInstalled(ctx),
            notificationGranted = DiscordNotificationListener.isGranted(ctx),
            notificationConnected = DiscordNotificationListener.connected() != null,
            accessibilityGranted = DiscordAccessibilityService.isGranted(ctx),
            accessibilityConnected = DiscordAccessibilityService.connected() != null,
        )
    }
}

@Composable
private fun rememberGrants(): Grants {
    val ctx = LocalContext.current
    var grants by remember { mutableStateOf(Grants.read(ctx)) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        // Neither permission fires a callback when it changes, so poll while we are on screen.
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                grants = Grants.read(ctx)
                delay(700)
            }
        }
    }
    return grants
}

@Composable
fun OnboardingScreen() {
    val ctx = LocalContext.current
    val grants = rememberGrants()
    val state by StatePublisher.get(ctx).state.collectAsState()
    val events by EventLog.entries.collectAsState()
    var dump by remember { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("WristCord", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Phone side. Controls Discord only through its own notification actions and its " +
                    "own on-screen buttons - no Discord API, no token, no third-party client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionsCard(grants)
            StatusCard(grants, state)
            DiagnosticsCard(onDump = { dump = it }, dump = dump)
            SelectorsCard()
            EventsCard(events.map { it.toString() })

            Spacer(Modifier.width(1.dp))
        }
    }
}

@Composable
private fun PermissionsCard(grants: Grants) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)

            PermissionRow(
                title = "Notification access",
                detail = "Fires the Mute and Disconnect actions on Discord's call notification. " +
                    "This is the path that works with the screen off.",
                granted = grants.notificationGranted,
                connected = grants.notificationConnected,
                buttonText = "Open notification access",
                onClick = { ctx.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            )

            HorizontalDivider()

            PermissionRow(
                title = "Accessibility service",
                detail = "Clicks Discord's own in-call controls for Deafen and the output " +
                    "picker. Needs the phone screen on.",
                granted = grants.accessibilityGranted,
                connected = grants.accessibilityConnected,
                buttonText = "Open accessibility settings",
                onClick = { ctx.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    connected: Boolean,
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(granted && connected, granted)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        granted && connected -> "Granted and running"
                        granted -> "Granted, but the service is not bound - toggle it off and on"
                        else -> "Not granted"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted && connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onClick) { Text(buttonText) }
    }
}

@Composable
private fun StatusDot(good: Boolean, partial: Boolean) {
    val color = when {
        good -> Color(0xFF3BA55D)
        partial -> Color(0xFFFAA61A)
        else -> Color(0xFFED4245)
    }
    Box(Modifier.size(14.dp).background(color, CircleShape))
}

@Composable
private fun StatusCard(grants: Grants, state: VoiceState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What the watch sees", style = MaterialTheme.typography.titleMedium)
            InfoLine("Discord installed", if (grants.discordInstalled) "yes" else "no")
            InfoLine("In a voice call", if (state.inCall) "yes" else "no")
            InfoLine("Channel", state.channel.ifBlank { "-" })
            InfoLine("Muted", state.muted.human())
            InfoLine("Deafened", state.deafened.human())
            InfoLine("Outputs", state.outputs.size.toString())
            if (state.blocker.isNotBlank()) {
                Text(
                    state.blocker,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun Toggle.human(): String = when (this) {
    Toggle.ON -> "yes"
    Toggle.OFF -> "no"
    Toggle.UNKNOWN -> "unknown (no matching notification action)"
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.width(140.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DiagnosticsCard(onDump: (String) -> Unit, dump: String) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Step 0 - discovery", style = MaterialTheme.typography.titleMedium)
            Text(
                "Join a voice call, then dump. Same text goes to logcat: adb logcat -s ${Probe.TAG}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDump(Probe.dumpNotifications(ctx)) }) {
                    Text("Notifications")
                }
                OutlinedButton(onClick = { onDump(Probe.dumpVoicePanel(ctx)) }) {
                    Text("Voice panel")
                }
                OutlinedButton(onClick = { onDump(Probe.dumpAudioOutputs(ctx)) }) {
                    Text("Audio")
                }
            }
            if (dump.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ctx.copyToClipboard(dump) }) { Text("Copy") }
                    TextButton(onClick = { ctx.saveDump(dump) }) { Text("Save to file") }
                    TextButton(onClick = { onDump("") }) { Text("Clear") }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        dump,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectorsCard() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Selectors",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            Text(
                "Regexes matched against Discord's action titles and view nodes. Blank = use the " +
                    "built-in default. Change them here instead of rebuilding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                DiscordSelectors.Slot.entries.forEach { slot ->
                    SelectorField(slot)
                }
                OutlinedButton(onClick = {
                    DiscordSelectors.Slot.entries.forEach { DiscordSelectors.setOverride(ctx, it, null) }
                    StatePublisher.get(ctx).refresh("selectors reset")
                }) { Text("Reset all to defaults") }
            }
        }
    }
}

@Composable
private fun SelectorField(slot: DiscordSelectors.Slot) {
    val ctx = LocalContext.current
    var value by remember(slot) { mutableStateOf(DiscordSelectors.override(ctx, slot).orEmpty()) }
    val valid = DiscordSelectors.isValid(value)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                if (DiscordSelectors.isValid(it)) {
                    DiscordSelectors.setOverride(ctx, slot, it)
                    StatePublisher.get(ctx).refresh("selector ${slot.prefKey} changed")
                }
            },
            label = { Text(slot.label) },
            placeholder = { Text(slot.default) },
            isError = !valid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (valid) slot.help else "Not a valid regex - not saved",
            style = MaterialTheme.typography.labelSmall,
            color = if (valid) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun EventsCard(events: List<String>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent activity", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { EventLog.clear() }) { Text("Clear") }
            }
            if (events.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                events.asReversed().take(30).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun Context.openSettings(action: String) {
    runCatching {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        EventLog.e("WristCord/UI", "could not open $action: ${it.message}")
    }
}

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("WristCord probe", text))
    EventLog.i("WristCord/UI", "probe output copied to clipboard")
}

private fun Context.saveDump(text: String) {
    val dir = getExternalFilesDir(null) ?: filesDir
    val file = File(dir, "wristcord-probe-${System.currentTimeMillis()}.txt")
    runCatching { file.writeText(text) }
        .onSuccess { EventLog.i("WristCord/UI", "saved ${file.absolutePath}") }
        .onFailure { EventLog.e("WristCord/UI", "save failed: ${it.message}") }
}
