package com.wristcord.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.wristcord.R
import com.wristcord.mobile.diagnostics.Probe
import com.wristcord.mobile.discord.Discord
import com.wristcord.mobile.discord.DiscordAccessibilityService
import com.wristcord.mobile.discord.DiscordNotificationListener
import com.wristcord.mobile.discord.DiscordSelectors
import com.wristcord.mobile.link.IconSync
import com.wristcord.mobile.link.StatePublisher
import com.wristcord.mobile.link.StatusService
import com.wristcord.mobile.util.EventLog
import com.wristcord.shared.Brand
import com.wristcord.shared.Capabilities
import com.wristcord.shared.IconVariant
import com.wristcord.shared.UpdateController
import com.wristcord.shared.Updater
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import kotlinx.coroutines.delay
import java.io.File

private data class Grants(
    val discordInstalled: Boolean,
    val notificationGranted: Boolean,
    val notificationConnected: Boolean,
    val accessibilityGranted: Boolean,
    val accessibilityConnected: Boolean,
    /** This phone is advertising wristcord_phone to the Data Layer. */
    val advertising: Boolean,
    /** A watch running WristCord is reachable right now. */
    val watchReachable: Boolean,
) {
    companion object {
        fun read(ctx: Context) = Grants(
            discordInstalled = Discord.isInstalled(ctx),
            notificationGranted = DiscordNotificationListener.isGranted(ctx),
            notificationConnected = DiscordNotificationListener.connected() != null,
            accessibilityGranted = DiscordAccessibilityService.isGranted(ctx),
            accessibilityConnected = DiscordAccessibilityService.connected() != null,
            advertising = false,
            watchReachable = false,
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
    // The Data Layer checks are round trips, so they run on their own slower loop.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val advertising = Capabilities.localAdvertises(ctx, Wire.CAPABILITY_PHONE)
                val reachable = Capabilities.peerReachable(ctx, Wire.CAPABILITY_WATCH)
                grants = grants.copy(advertising = advertising, watchReachable = reachable)
                delay(5_000)
            }
        }
    }
    return grants
}

@Composable
fun OnboardingScreen(updates: UpdateController) {
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
            BrandHeader()
            Text(
                "The voice bridge. Since 2.0 the watch signs in to Discord itself and reads your " +
                    "real voice state; this app is what presses mute, deafen, output and leave on " +
                    "the phone's own Discord call - through Discord's notification actions and " +
                    "on-screen buttons, because Discord's servers do not let one device mute " +
                    "another. It holds no token and never talks to Discord itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionsCard(grants)
            WatchLinkCard(grants)
            StatusCard(grants, state)
            BackgroundCard()
            AppearanceCard()
            UpdateCard(updates)
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

/**
 * The watch link, made visible.
 *
 * This exists because of a real bug: R8's resource shrinker deleted the capability array that
 * Play services reads to match the two apps, and the devices stopped finding each other with no
 * crash, no log and nothing on screen. Anything that can fail silently should have a row that
 * says so.
 */
@Composable
private fun WatchLinkCard(grants: Grants) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watch link", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(grants.advertising, grants.advertising)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "This phone is discoverable",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (grants.advertising) {
                            "Advertising wristcord_phone"
                        } else {
                            "Not advertising - the watch cannot find this phone"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (grants.advertising) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(grants.watchReachable, grants.watchReachable)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Watch reachable", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (grants.watchReachable) {
                            "A watch running WristCord is connected"
                        } else {
                            "No watch found - check it is in range and has the same build"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (grants.watchReachable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            if (!grants.watchReachable) {
                Text(
                    "Both APKs must share the applicationId and the signing certificate, and " +
                        "both must be the same release. Play services will not route between " +
                        "them otherwise.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/**
 * Running in the background. Off by default - WristCord works without it, because the
 * notification listener already keeps the process alive and Play services starts the message
 * listener on demand. What this buys is a visible link status and some insurance against
 * aggressive battery managers.
 *
 * Two things have to line up before the switch can do anything, and both of them can fail in
 * ways that are the user's to resolve, so both are reported here rather than thrown:
 * POST_NOTIFICATIONS has to be granted (API 33+), and the system has to actually allow the
 * foreground service to start.
 */
@Composable
private fun BackgroundCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(StatusService.isEnabled(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val problem by StatusService.problem.collectAsState()

    val askForNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        denied = !granted
        if (granted) {
            enabled = true
            StatusService.setEnabled(ctx, true)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Status notification", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps WristCord running with a quiet notification showing the watch " +
                            "link and the call, plus Mute and Leave in the shade.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        denied = false
                        when {
                            !want -> {
                                enabled = false
                                StatusService.setEnabled(ctx, false)
                            }

                            StatusService.canPostNotifications(ctx) -> {
                                enabled = true
                                StatusService.setEnabled(ctx, true)
                            }

                            else -> askForNotifications.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    },
                )
            }

            val trouble = when {
                denied -> "Notification permission denied, so there is nowhere to show this. " +
                    "Grant it in Android settings and try again."

                problem != null -> "Android would not start it: $problem"
                else -> null
            }

            if (trouble != null) {
                Text(
                    trouble,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { ctx.openAppSettings() }) { Text("App settings") }
            }

            Text(
                "Optional. Everything works with this off.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Icon picker. Chosen here for both devices - the watch is told over the Data Layer, because
 * both APKs ship the same four aliases.
 */
@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(IconVariant.current(ctx)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App icon", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies to the phone and the watch. The watch follows the next time it is in range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconVariant.entries.forEach { variant ->
                    IconChoice(
                        variant = variant,
                        selected = variant == current,
                        onClick = {
                            current = variant
                            IconSync.choose(ctx, variant)
                        },
                    )
                }
            }
            Text(
                current.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IconChoice(variant: IconVariant, selected: Boolean, onClick: () -> Unit) {
    val preview = when (variant) {
        IconVariant.HOMAGE -> R.drawable.ic_launcher_ring
        IconVariant.PIXEL -> R.drawable.ic_launcher_ring_dark
        IconVariant.BUBBLE -> R.drawable.ic_launcher_bubble
        IconVariant.LINK -> R.drawable.ic_launcher_link
    }
    val ground = if (variant == IconVariant.PIXEL) Color.White else Color(Brand.BLURPLE)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ground)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(preview),
                contentDescription = variant.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(58.dp),
            )
        }
        Text(
            variant.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The name in Google's four product colours - the same palette the watch uses. */
@Composable
private fun BrandHeader() {
    Text(
        text = buildAnnotatedString {
            val palette = listOf(
                Brand.BLUE, Brand.RED, Brand.YELLOW, Brand.GREEN,
                Brand.BLUE, Brand.RED, Brand.YELLOW, Brand.GREEN, Brand.BLUE,
            )
            "WristCord".forEachIndexed { index, ch ->
                withStyle(
                    SpanStyle(
                        color = Color(palette[index % palette.size]),
                        fontWeight = FontWeight.Bold,
                    )
                ) { append(ch) }
            }
        },
        style = MaterialTheme.typography.headlineMedium,
    )
}

/**
 * Updates pulled from the project's GitHub releases. This card only ever updates the phone -
 * the watch has the same card in its own Settings, because neither device can install for the
 * other.
 */
@Composable
private fun UpdateCard(updates: UpdateController) {
    val ctx = LocalContext.current
    val status by updates.status.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updates", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    "v" + updates.currentVersionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                updates.summary(status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status is Updater.Status.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (status is Updater.Status.Downloading) {
                val percent = (status as Updater.Status.Downloading).percent
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val current = status) {
                    is Updater.Status.Available -> {
                        Button(onClick = { updates.install(current.release) }) {
                            Text("Install " + current.release.tag)
                        }
                        if (!Updater.canRequestInstalls(ctx)) {
                            OutlinedButton(onClick = { ctx.openInstallPermission() }) {
                                Text("Allow installs")
                            }
                        }
                    }

                    is Updater.Status.Downloading,
                    is Updater.Status.Verifying,
                    is Updater.Status.Installing,
                    is Updater.Status.Checking,
                    -> Unit

                    else -> OutlinedButton(onClick = { updates.check() }) {
                        Text("Check for updates")
                    }
                }
                TextButton(onClick = { ctx.openReleases() }) { Text("Releases") }
            }

            Text(
                "The download must be signed with the same key as this app or it is refused " +
                    "before the installer ever sees it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun Context.openInstallPermission() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun Context.openReleases() {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
