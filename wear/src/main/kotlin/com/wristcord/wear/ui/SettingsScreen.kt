package com.wristcord.wear.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import android.app.RemoteInput
import com.wristcord.BuildConfig
import com.wristcord.R
import com.wristcord.discord.Cdn
import com.wristcord.discord.proto.Theme
import com.wristcord.discord.state.Snapshot
import com.wristcord.shared.IconVariant
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.discord.ThemePrefs
import com.wristcord.wear.ui.components.Avatar
import com.wristcord.wear.ui.components.Caption
import com.wristcord.wear.ui.components.ListScreen
import com.wristcord.wear.ui.components.RowIconSize
import com.wristcord.wear.ui.components.radioColors
import com.wristcord.wear.ui.components.row
import com.wristcord.wear.ui.components.statusColor
import com.wristcord.wear.ui.components.statusLabel
import com.wristcord.wear.ui.theme.discord
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    snapshot: Snapshot,
    signedIn: Boolean,
    updateSummary: String,
    onOpenStatus: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenIcons: () -> Unit,
    onOpenButtons: () -> Unit,
    onOpenUpdates: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = discord
    var confirmSignOut by remember { mutableStateOf(false) }
    val theme by ThemePrefs.resolved.collectAsStateWithLifecycle()

    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Settings") } }
        val me = snapshot.me
        if (signedIn && me != null) {
            row(spec, "account") {
                val status = snapshot.settings.status.ifBlank { "online" }
                Button(
                    onClick = onOpenStatus,
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Avatar(Cdn.avatar(me, 64), me.displayName, RowIconSize, status = status) },
                    label = { Text(me.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(statusLabel(status), maxLines = 1) },
                )
            }
        }
        row(spec, "theme") {
            Button(
                onClick = onOpenTheme,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Palette, contentDescription = null, tint = c.iconBrand) },
                label = { Text("Theme") },
                secondaryLabel = { Text(themeSummary(theme), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }

        row(spec, "remote") { ListHeader(modifier = modifier) { Text("Voice remote") } }
        row(spec, "buttons") {
            Button(
                onClick = onOpenButtons,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.GridView, contentDescription = null, tint = c.iconDefault) },
                label = { Text("Buttons") },
                secondaryLabel = { Text("Choose what goes where") },
            )
        }
        row(spec, "headset") {
            Caption("Calls on the watch need a Bluetooth headset connected to it, and never use the watch's own speaker or mic.", modifier)
        }

        row(spec, "app") { ListHeader(modifier = modifier) { Text("App") } }
        row(spec, "icon") {
            Button(
                onClick = onOpenIcons,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Apps, contentDescription = null, tint = c.iconDefault) },
                label = { Text("App icon") },
                secondaryLabel = { Text(IconVariant.current(LocalContext.current).label) },
            )
        }
        row(spec, "updates") {
            Button(
                onClick = onOpenUpdates,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Download, contentDescription = null, tint = c.iconDefault) },
                label = { Text("Updates") },
                secondaryLabel = { Text(updateSummary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            )
        }
        if (signedIn) row(spec, "signout") {
            Button(
                onClick = { confirmSignOut = true },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(contentColor = c.textCritical, iconColor = c.iconCritical),
                icon = { Icon(Icons.Rounded.Logout, contentDescription = null) },
                label = { Text("Sign out") },
            )
        }
        row(spec, "about") {
            Caption("WristCord ${BuildConfig.VERSION_NAME}\nUnofficial · not made by or affiliated with Discord", modifier)
        }
    }

    AlertDialog(
        visible = confirmSignOut,
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?", textAlign = TextAlign.Center) },
        text = { Text("This ends the watch's Discord session everywhere.", textAlign = TextAlign.Center) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmSignOut = false
                onSignOut()
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmSignOut = false }) },
    )
}

private fun themeSummary(r: ThemePrefs.Resolved): String = when (r.choice) {
    ThemePrefs.Choice.FOLLOW -> "Match Discord · ${themeName(r.theme)}" + if (r.gradient != null) " + Nitro" else ""
    else -> r.choice.label
}

fun themeName(t: Theme) = when (t) {
    Theme.DARK -> "Dark"
    Theme.ASH -> "Ash"
    Theme.ONYX -> "Onyx"
    Theme.LIGHT -> "Light"
}

/**
 * Online status, set the way Discord's own apps set it - through the account's settings, so
 * it changes on your phone and desktop too.
 */
@Composable
fun StatusScreen(snapshot: Snapshot, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val client = WristCordApp.of(ctx).discord
    val scope = rememberCoroutineScope()
    val current = snapshot.settings.status.ifBlank { "online" }
    val custom = snapshot.settings.customStatus?.takeIf { it.isActive() }
    var failed by remember { mutableStateOf(false) }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_REPLY) }?.toString()
        if (!text.isNullOrBlank()) scope.launch { failed = !client.setCustomStatus(text.take(128)) }
    }

    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Status") } }
        for (s in listOf("online", "idle", "dnd", "invisible")) row(spec, s) {
            RadioButton(
                selected = current == s,
                onSelect = {
                    scope.launch {
                        failed = !client.setStatus(s)
                        if (!failed) onDone()
                    }
                },
                modifier = modifier,
                transformation = transformation,
                colors = radioColors(),
                icon = { Box(Modifier.size(12.dp).clip(CircleShape).background(statusColor(s))) },
                label = { Text(statusLabel(s)) },
            )
        }
        if (failed) row(spec, "err") { Caption("Discord did not accept that - try again", modifier, color = discord.textCritical) }
        row(spec, "custom") {
            Button(
                onClick = { runCatching { input.launch(replyIntent("Custom status")) } },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.EditNote, contentDescription = null) },
                label = { Text("Custom status") },
                secondaryLabel = { Text(custom?.let { listOf(it.emojiName, it.text).filter(String::isNotBlank).joinToString(" ") } ?: "Not set", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        if (custom != null) row(spec, "clear") {
            Button(
                onClick = { scope.launch { failed = !client.setCustomStatus("") } },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                label = { Text("Clear custom status") },
            )
        }
    }
}

/** Discord's themes, plus following the account so the watch changes when the phone does. */
@Composable
fun ThemeScreen() {
    val ctx = LocalContext.current
    val r by ThemePrefs.resolved.collectAsStateWithLifecycle()
    val accountTheme = remember(r) { ThemePrefs.accountTheme(ctx) }
    val accountGradient = remember(r) { ThemePrefs.accountGradient(ctx) }
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Theme") } }
        for (choice in ThemePrefs.Choice.entries) row(spec, choice.name) {
            RadioButton(
                selected = r.choice == choice,
                onSelect = { ThemePrefs.setChoice(ctx, choice) },
                modifier = modifier,
                transformation = transformation,
                colors = radioColors(),
                label = { Text(choice.label) },
                secondaryLabel = if (choice == ThemePrefs.Choice.FOLLOW) { { Text("Now ${themeName(accountTheme)}") } } else null,
            )
        }
        row(spec, "nitro") {
            SwitchButton(
                checked = r.gradients,
                onCheckedChange = { ThemePrefs.setGradients(ctx, it) },
                enabled = r.choice == ThemePrefs.Choice.FOLLOW,
                modifier = modifier,
                transformation = transformation,
                label = { Text("Nitro gradient") },
                secondaryLabel = { Text(if (accountGradient != null) "From your account" else "When your account uses one") },
            )
        }
        row(spec, "note") { Caption("Colours come straight from Discord's own theme tokens.", modifier) }
    }
}

/**
 * Slot editing, borrowed from the watch-face complication editor: a live miniature of the
 * remote with every position outlined, and a list of what can go in each.
 */
@Composable
fun ButtonsScreen(
    assignments: Map<Slot, RemoteAction>,
    onEditSlot: (Slot) -> Unit,
    onReset: () -> Unit,
) {
    val c = discord
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("Buttons") } }
        row(spec, "hint") { Caption("Tap a position to change what it does.", modifier) }
        row(spec, "preview") { SlotEditorPreview(assignments, onEditSlot, modifier) }
        for (slot in Slot.entries) row(spec, slot.id) {
            val action = assignments[slot] ?: slot.default
            Button(
                onClick = { onEditSlot(slot) },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(painterResource(action.icon(engaged = false)), contentDescription = null, tint = c.iconStrong, modifier = Modifier.size(20.dp)) },
                secondaryLabel = { Text(action.label) },
                label = { Text(slot.label) },
            )
        }
        row(spec, "reset") {
            Button(
                onClick = onReset,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Reset layout") },
            )
        }
    }
}

@Composable
private fun SlotEditorPreview(assignments: Map<Slot, RemoteAction>, onEditSlot: (Slot) -> Unit, modifier: Modifier) {
    val c = discord
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(c.baseLowest, CircleShape)
            .border(1.dp, c.borderNormal, CircleShape),
    ) {
        val size: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        SlotTarget(assignments[Slot.LEFT] ?: Slot.LEFT.default, size * 0.215f,
            Modifier.align(Alignment.Center).offset(x = size * -0.315f, y = size * -0.02f)) { onEditSlot(Slot.LEFT) }
        SlotTarget(assignments[Slot.CENTRE] ?: Slot.CENTRE.default, size * 0.36f,
            Modifier.align(Alignment.Center).offset(y = size * -0.02f)) { onEditSlot(Slot.CENTRE) }
        SlotTarget(assignments[Slot.RIGHT] ?: Slot.RIGHT.default, size * 0.215f,
            Modifier.align(Alignment.Center).offset(x = size * 0.315f, y = size * -0.02f)) { onEditSlot(Slot.RIGHT) }
        SlotTarget(assignments[Slot.BOTTOM] ?: Slot.BOTTOM.default, size * 0.20f,
            Modifier.align(Alignment.Center).offset(x = size * -0.085f, y = size * 0.345f)) { onEditSlot(Slot.BOTTOM) }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * 0.145f, y = size * 0.345f)
                .size(size * 0.145f)
                .clip(CircleShape)
                .background(c.surfaceHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, tint = c.iconMuted, modifier = Modifier.size(size * 0.07f))
        }
    }
}

@Composable
private fun SlotTarget(action: RemoteAction, diameter: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = remoteColors(action, engaged = false)
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(colors.fill, CircleShape)
            .border(1.dp, colors.accent.copy(alpha = 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(action.icon(engaged = false)), contentDescription = action.label, tint = colors.tint, modifier = Modifier.size(diameter * 0.42f))
    }
}

/** The "what can go here" list, reached by tapping a position. */
@Composable
fun SlotPickerScreen(slot: Slot, current: RemoteAction, onChoose: (RemoteAction) -> Unit) {
    val c = discord
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text(slot.label) } }
        for (action in RemoteAction.entries) row(spec, action.id) {
            val selected = action == current
            Button(
                onClick = { onChoose(action) },
                modifier = modifier,
                transformation = transformation,
                colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    else Icon(painterResource(action.icon(engaged = false)), contentDescription = null, tint = c.iconStrong, modifier = Modifier.size(20.dp))
                },
                secondaryLabel = { Text(action.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                label = { Text(action.label) },
            )
        }
    }
}


/** The four launcher looks from 1.x, now picked on the watch itself. */
@Composable
fun IconScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(IconVariant.current(ctx)) }
    ListScreen { spec ->
        row(spec, "h") { ListHeader(modifier = modifier) { Text("App icon") } }
        for (v in IconVariant.entries) row(spec, v.id) {
            RadioButton(
                selected = current == v,
                onSelect = {
                    IconVariant.apply(ctx, v)
                    current = v
                    onDone()
                },
                modifier = modifier,
                transformation = transformation,
                colors = radioColors(),
                label = { Text(v.label) },
                secondaryLabel = { Text(v.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        row(spec, "note") { Caption("The launcher may take a moment to show the new icon.", modifier) }
    }
}
