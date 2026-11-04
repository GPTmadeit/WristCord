package com.wristcord.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureClickIndicatorState
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import com.wristcord.R
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import kotlinx.coroutines.launch
import com.wristcord.wear.ui.theme.discord
import com.wristcord.wear.ui.theme.ThemedBackground

/**
 * The remote, laid out the way YouTube Music lays out its transport: one oversized control in
 * the middle with two satellites flanking it, a secondary control below, and the context you
 * need on one line rather than a list you have to read. The server artwork sits behind it all
 * the way album art sits behind YTM's controls.
 *
 * Which action sits in which position comes from [assignments] (Settings), so the big centre
 * disc is whatever you most want to hit blind - mute by default. That same centre action is
 * what the double-pinch gesture fires.
 */
@Composable
fun RemoteScreen(
    state: VoiceState,
    error: String?,
    pending: RemoteAction?,
    assignments: Map<Slot, RemoteAction>,
    ambient: AmbientState,
    artwork: ImageBitmap?,
    onAction: (RemoteAction) -> Unit,
    onOpenSettings: () -> Unit,
    /** Discord's own context for the call: "Server · on your phone", or null when signed out. */
    context: String? = null,
    onOpenParticipants: (() -> Unit)? = null,
) {
    val centre = assignments[Slot.CENTRE] ?: Slot.CENTRE.default
    val left = assignments[Slot.LEFT] ?: Slot.LEFT.default
    val right = assignments[Slot.RIGHT] ?: Slot.RIGHT.default
    val bottom = assignments[Slot.BOTTOM] ?: Slot.BOTTOM.default

    // Double pinch on Pixel Watch 3 and later. The library no-ops on hardware without it, so
    // this costs nothing on older watches.
    val gestureConfiguration = rememberOneHandedGestureConfiguration(
        action = OneHandedGestureAction.Primary,
        priority = OneHandedGesturePriority.Clickable,
    )
    val indicatorState = remember { OneHandedGestureClickIndicatorState() }
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    val c = discord
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (ambient.isAmbient) Color.Black else c.baseLowest),
    ) {
        val size: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val nudge = ambient.burnInOffset

        val centreDiameter = size * 0.34f
        val sideDiameter = size * 0.215f
        val bottomDiameter = size * 0.20f
        val gearDiameter = size * 0.145f

        // Artwork lifted from Discord's own call notification. Never in ambient - it is the
        // most expensive thing on screen and ambient is meant to light almost no pixels.
        if (artwork != null && !ambient.isAmbient) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.34f),
            )
            // The source is only 160px, so blowing it up to fill the watch is already soft -
            // no blur pass needed. This scrim is what keeps the glyphs legible on top.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            0.75f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.80f),
                        )
                    )
            )
        }

        Text(
            text = headline(state),
            style = MaterialTheme.typography.labelMedium,
            color = if (ambient.isAmbient) c.textMuted else c.headingPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * -0.325f + nudge)
                .fillMaxWidth(0.66f)
                .then(
                    if (onOpenParticipants != null && !ambient.isAmbient && state.inCall) Modifier.clickable(onClick = onOpenParticipants)
                    else Modifier
                ),
        )

        RemoteButton(
            action = left,
            diameter = sideDiameter,
            engaged = left.engagedIn(state),
            busy = pending == left,
            ambient = ambient,
            enabled = left != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.315f + nudge, y = size * -0.05f + nudge),
            onClick = { onAction(left) },
        )

        RemoteButton(
            action = centre,
            diameter = centreDiameter,
            engaged = centre.engagedIn(state),
            busy = pending == centre,
            ambient = ambient,
            enabled = centre != RemoteAction.NONE,
            interactionSource = interactionSource,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * -0.05f + nudge)
                .oneHandedGesture(
                    gestureConfiguration = gestureConfiguration,
                    onGestureLabel = centre.centreLabel(state),
                    // A pinch is deliberate in a way a touch is not, so unlike tapping it
                    // stays live in ambient - that is the point of a hands-free remote.
                    enabledInAmbient = true,
                    interactionSource = interactionSource,
                    onGestureAvailable = { scope.launch { indicatorState.showIndicator() } },
                    onGesture = { onAction(centre) },
                ),
            decorate = { content ->
                OneHandedGestureClickIndicator(
                    gestureConfiguration = gestureConfiguration,
                    state = indicatorState,
                    content = content,
                )
            },
            onClick = { onAction(centre) },
        )

        RemoteButton(
            action = right,
            diameter = sideDiameter,
            engaged = right.engagedIn(state),
            busy = pending == right,
            ambient = ambient,
            enabled = right != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * 0.315f + nudge, y = size * -0.05f + nudge),
            onClick = { onAction(right) },
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * 0.175f + nudge)
                .fillMaxWidth(0.72f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = centre.centreLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = if (ambient.isAmbient) Color.White else c.headingPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subline(state, error, centre, context),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    ambient.isAmbient -> c.textMuted
                    error != null || state.blocker.isNotBlank() -> c.textCritical
                    else -> c.textMuted
                },
                maxLines = if (context.isNullOrBlank()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        RemoteButton(
            action = bottom,
            diameter = bottomDiameter,
            engaged = bottom.engagedIn(state),
            busy = pending == bottom,
            ambient = ambient,
            enabled = bottom != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.085f + nudge, y = size * 0.37f + nudge),
            onClick = { onAction(bottom) },
        )

        // Settings is fixed, like YTM's fixed controls - it is never one of the four slots,
        // because losing your way back into Settings would be a trap.
        if (!ambient.isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = size * 0.145f, y = size * 0.37f),
            ) {
                SettingsDot(diameter = gearDiameter, onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun SettingsDot(diameter: Dp, onClick: () -> Unit) {
    val c = discord
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(c.surfaceHigh, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = c.iconDefault,
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

private fun headline(state: VoiceState): String = when {
    state.channel.isNotBlank() -> state.channel
    state.inCall -> "In a call"
    else -> "WristCord"
}

private fun subline(state: VoiceState, error: String?, centre: RemoteAction, context: String?): String = when {
    error != null -> error
    state.blocker.isNotBlank() -> state.blocker
    !state.inCall -> "Not in a voice call"
    // The red disc and its "Unmute"/"Undeafen" label already say what state you are in; the
    // one line under it is better spent on where the call is.
    !context.isNullOrBlank() -> context
    else -> centre.statusIn(state)
}

/**
 * The remote with always-on: only this screen keeps the display up in ambient, the way a
 * media player does. Everything else returns to the watch face like any other app.
 */
@Composable
fun RemoteHost(
    state: VoiceState,
    error: String?,
    pending: RemoteAction?,
    assignments: Map<Slot, RemoteAction>,
    artwork: ImageBitmap?,
    context: String?,
    onAction: (RemoteAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenParticipants: (() -> Unit)?,
    onTakeOver: () -> Unit,
) {
    val ambient = rememberAmbientState()
    Box(Modifier.fillMaxSize()) {
        if (state.inCall && !state.onWatch) {
            RemoteElsewhere(state, ambient, onTakeOver = onTakeOver, onLeave = { onAction(RemoteAction.DISCONNECT) }, onOpenParticipants = onOpenParticipants)
        } else {
            RemoteScreen(
                state = state,
                error = error,
                pending = pending,
                assignments = assignments,
                ambient = ambient,
                artwork = artwork,
                onAction = onAction,
                onOpenSettings = onOpenSettings,
                context = context,
                onOpenParticipants = onOpenParticipants,
            )
        }
        if (!ambient.isAmbient) androidx.wear.compose.material3.TimeText()
    }
}

/**
 * A call running on another device: Discord's view of it, and - with a headset connected - one
 * big button that moves it to the watch. Nothing here pretends to mute a call it cannot reach.
 */
@Composable
private fun RemoteElsewhere(
    state: VoiceState,
    ambient: AmbientState,
    onTakeOver: () -> Unit,
    onLeave: () -> Unit,
    onOpenParticipants: (() -> Unit)?,
) {
    val c = discord
    BoxWithConstraints(Modifier.fillMaxSize().background(if (ambient.isAmbient) Color.Black else c.baseLowest)) {
        val size: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        Text(
            text = state.channel.ifBlank { "In a call" },
            style = MaterialTheme.typography.labelMedium,
            color = if (ambient.isAmbient) c.textMuted else c.headingPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = size * -0.325f)
                .fillMaxWidth(0.66f)
                .then(if (onOpenParticipants != null && !ambient.isAmbient) Modifier.clickable(onClick = onOpenParticipants) else Modifier),
        )
        val disc = size * 0.34f
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = size * -0.05f)
                .size(disc)
                .clip(CircleShape)
                .background(
                    when {
                        ambient.isAmbient -> Color.Black
                        state.canMoveHere -> c.backgroundBrand
                        else -> c.surfaceHighest
                    },
                    CircleShape,
                )
                .clickable(enabled = state.canMoveHere && !ambient.isAmbient, onClick = onTakeOver)
                .semantics { contentDescription = if (state.canMoveHere) "Move the call to the watch" else "Connect a headset to move the call here" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (state.canMoveHere) R.drawable.ic_headset else R.drawable.ic_headset_off),
                contentDescription = null,
                tint = if (state.canMoveHere) c.controlPrimaryText else c.iconMuted,
                modifier = Modifier.size(disc * 0.42f),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = size * 0.175f)
                .fillMaxWidth(0.74f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (state.canMoveHere) "Move here" else "Needs a headset",
                style = MaterialTheme.typography.labelLarge,
                color = if (ambient.isAmbient) Color.White else c.headingPrimary,
                maxLines = 1,
            )
            Text(
                listOfNotNull(
                    state.blocker.takeIf { it.isNotBlank() },
                    when {
                        state.deafened == Toggle.ON -> "deafened"
                        state.muted == Toggle.ON -> "muted"
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        RemoteButton(
            action = RemoteAction.DISCONNECT,
            diameter = size * 0.20f,
            engaged = false,
            busy = false,
            ambient = ambient,
            modifier = Modifier.align(Alignment.Center).offset(y = size * 0.37f),
            onClick = onLeave,
        )
    }
}
