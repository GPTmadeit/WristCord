package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import com.wristcord.shared.RemoteAction
import com.wristcord.wear.ui.theme.discord

/**
 * One circular control.
 *
 * Resting state is a neutral disc in the current Discord theme; engaged mute/deafen fill red
 * the way Discord's own voice panel shows them, and Leave is always the red hang-up. Same
 * shape language as the YouTube Music transport row, which is what makes the centre button
 * reachable without looking.
 *
 * In ambient everything collapses to an outline on black - no fills, no colour - so the panel
 * draws almost nothing.
 *
 * [decorate] exists so the centre button can wrap its glyph in the one-handed-gesture
 * indicator without this composable having to know that gestures exist.
 */
@Composable
fun RemoteButton(
    action: RemoteAction,
    diameter: Dp,
    engaged: Boolean,
    busy: Boolean,
    ambient: AmbientState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    decorate: @Composable (@Composable () -> Unit) -> Unit = { it() },
    onClick: () -> Unit,
) {
    val iconSize = diameter * 0.42f
    val source = interactionSource ?: remember { MutableInteractionSource() }

    val fill: Color
    val tint: Color
    val ring: Color
    val themed = remoteColors(action, engaged)
    val c = discord

    if (ambient.isAmbient) {
        fill = Color.Black
        tint = if (engaged) Color.White else c.iconMuted
        ring = if (engaged) Color.White else c.borderStrong
    } else {
        fill = themed.fill
        tint = themed.tint
        ring = if (action == RemoteAction.NONE) c.borderNormal else Color.Transparent
    }

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .then(
                if (ring == Color.Transparent) Modifier
                else Modifier.border(if (ambient.lowBit) 2.dp else 1.dp, ring, CircleShape)
            )
            .clickable(
                enabled = enabled && !ambient.isAmbient,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        decorate {
            Icon(
                painter = painterResource(action.icon(engaged)),
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(iconSize)
                    .alpha(if (busy) 0.45f else if (enabled) 1f else 0.35f),
            )
        }
    }
}
