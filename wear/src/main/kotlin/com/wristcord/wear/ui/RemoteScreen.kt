package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.wristcord.R
import com.wristcord.shared.Brand
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.VoiceState

/**
 * The remote, laid out the way YouTube Music lays out its transport: one oversized control in
 * the middle with two satellites flanking it, a secondary control below, and the context you
 * need on one line rather than a list you have to read.
 *
 * Which action sits in which position comes from [assignments] (Settings), so the big centre
 * disc is whatever you most want to hit blind - mute by default.
 */
@Composable
fun RemoteScreen(
    state: VoiceState,
    error: String?,
    pending: String?,
    assignments: Map<Slot, RemoteAction>,
    ambient: AmbientState,
    onAction: (RemoteAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val centre = assignments[Slot.CENTRE] ?: Slot.CENTRE.default
    val left = assignments[Slot.LEFT] ?: Slot.LEFT.default
    val right = assignments[Slot.RIGHT] ?: Slot.RIGHT.default
    val bottom = assignments[Slot.BOTTOM] ?: Slot.BOTTOM.default

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val size: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val nudge = ambient.burnInOffset

        val centreDiameter = size * 0.36f
        val sideDiameter = size * 0.215f
        val bottomDiameter = size * 0.20f
        val gearDiameter = size * 0.145f

        // Header: channel, or why there is nothing to control.
        Text(
            text = headline(state),
            style = MaterialTheme.typography.labelMedium,
            color = if (ambient.isAmbient) Color(Brand.ON_SURFACE_VARIANT) else Color(Brand.ON_SURFACE),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * -0.325f + nudge)
                .fillMaxWidth(0.66f),
        )

        RemoteButton(
            action = left,
            diameter = sideDiameter,
            engaged = left.engagedIn(state),
            busy = pending == left.path,
            ambient = ambient,
            enabled = left != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.315f + nudge, y = size * -0.02f + nudge),
            onClick = { onAction(left) },
        )

        RemoteButton(
            action = centre,
            diameter = centreDiameter,
            engaged = centre.engagedIn(state),
            busy = pending == centre.path,
            ambient = ambient,
            enabled = centre != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * -0.02f + nudge),
            onClick = { onAction(centre) },
        )

        RemoteButton(
            action = right,
            diameter = sideDiameter,
            engaged = right.engagedIn(state),
            busy = pending == right.path,
            ambient = ambient,
            enabled = right != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * 0.315f + nudge, y = size * -0.02f + nudge),
            onClick = { onAction(right) },
        )

        // One line of truth under the centre button: the error if there is one, otherwise what
        // the centre control currently reads as.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = nudge, y = size * 0.195f + nudge)
                .fillMaxWidth(0.72f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = centre.centreLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = if (ambient.isAmbient) Color.White else Color(Brand.ON_SURFACE),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subline(state, error, centre),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    ambient.isAmbient -> Color(Brand.OUTLINE)
                    error != null || state.blocker.isNotBlank() -> MaterialTheme.colorScheme.error
                    else -> Color(Brand.ON_SURFACE_VARIANT)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        RemoteButton(
            action = bottom,
            diameter = bottomDiameter,
            engaged = bottom.engagedIn(state),
            busy = pending == bottom.path,
            ambient = ambient,
            enabled = bottom != RemoteAction.NONE,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.085f + nudge, y = size * 0.345f + nudge),
            onClick = { onAction(bottom) },
        )

        // Settings is fixed, like YTM's fixed controls - it is never one of the four slots,
        // because losing your way back into Settings would be a trap.
        if (!ambient.isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = size * 0.145f, y = size * 0.345f),
            ) {
                SettingsDot(diameter = gearDiameter, onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun SettingsDot(diameter: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color(Brand.SURFACE), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = Color(Brand.ON_SURFACE_VARIANT),
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

private fun headline(state: VoiceState): String = when {
    state.channel.isNotBlank() -> state.channel
    state.inCall -> "In a call"
    else -> "WristCord"
}

private fun subline(state: VoiceState, error: String?, centre: RemoteAction): String = when {
    error != null -> error
    state.blocker.isNotBlank() -> state.blocker
    !state.inCall -> "Not in a voice call"
    else -> centre.statusIn(state)
}
