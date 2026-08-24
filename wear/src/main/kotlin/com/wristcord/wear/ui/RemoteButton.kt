package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import com.wristcord.shared.Brand
import com.wristcord.shared.RemoteAction

/**
 * One circular control.
 *
 * Resting state is a dark disc with the action's Google colour on the glyph; engaged state
 * fills the disc with that colour. Same shape language as the YouTube Music transport row,
 * which is what makes the centre button reachable without looking.
 *
 * In ambient everything collapses to an outline on black - no fills, no colour - so the panel
 * draws almost nothing.
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
    onClick: () -> Unit,
) {
    val iconSize = diameter * 0.42f

    val fill: Color
    val tint: Color
    val ring: Color

    if (ambient.isAmbient) {
        fill = Color.Black
        tint = if (engaged) Color.White else Color(Brand.ON_SURFACE_VARIANT)
        ring = if (engaged) Color.White else Color(Brand.OUTLINE)
    } else if (engaged) {
        fill = action.color
        tint = action.onColor
        ring = Color.Transparent
    } else {
        fill = Color(Brand.SURFACE_HIGH)
        tint = action.color
        ring = Color.Transparent
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
            .clickable(enabled = enabled && !ambient.isAmbient, onClick = onClick)
            .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
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
