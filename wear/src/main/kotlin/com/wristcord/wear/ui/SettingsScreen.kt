package com.wristcord.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wristcord.R
import com.wristcord.shared.Brand
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot

/**
 * Slot editing, borrowed wholesale from the watch-face complication editor: you get a live
 * miniature of the thing you are editing, each position is a tappable target with a dashed
 * outline, and picking one drops you into a list of what can go there.
 */
@Composable
fun SettingsScreen(
    assignments: Map<Slot, RemoteAction>,
    updateSummary: String,
    onEditSlot: (Slot) -> Unit,
    onReset: () -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text("Buttons") }

            Text(
                "Tap a position to change what it does.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(Brand.ON_SURFACE_VARIANT),
                textAlign = TextAlign.Center,
            )

            SlotEditorPreview(assignments = assignments, onEditSlot = onEditSlot)

            Slot.entries.forEach { slot ->
                val action = assignments[slot] ?: slot.default
                Button(
                    onClick = { onEditSlot(slot) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            painter = painterResource(action.icon(engaged = false)),
                            contentDescription = null,
                            tint = action.color,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    secondaryLabel = { Text(action.label) },
                    label = { Text(slot.label) },
                )
            }

            Button(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Reset layout") },
            )

            ListHeader { Text("App") }

            Button(
                onClick = onOpenUpdates,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = Color(Brand.BLUE),
                        modifier = Modifier.size(20.dp),
                    )
                },
                secondaryLabel = { Text(updateSummary) },
                label = { Text("Updates") },
            )
        }
    }
}

/**
 * A non-functional miniature of the remote. Every position is outlined the way an empty
 * complication slot is, so it reads as "these are the things you can change".
 */
@Composable
private fun SlotEditorPreview(
    assignments: Map<Slot, RemoteAction>,
    onEditSlot: (Slot) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(Color.Black, CircleShape)
            .border(1.dp, Color(0xFF3C4043), CircleShape),
    ) {
        val size: Dp = if (maxWidth < maxHeight) maxWidth else maxHeight

        SlotTarget(
            action = assignments[Slot.LEFT] ?: Slot.LEFT.default,
            diameter = size * 0.215f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.315f, y = size * -0.02f),
            onClick = { onEditSlot(Slot.LEFT) },
        )
        SlotTarget(
            action = assignments[Slot.CENTRE] ?: Slot.CENTRE.default,
            diameter = size * 0.36f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = size * -0.02f),
            onClick = { onEditSlot(Slot.CENTRE) },
        )
        SlotTarget(
            action = assignments[Slot.RIGHT] ?: Slot.RIGHT.default,
            diameter = size * 0.215f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * 0.315f, y = size * -0.02f),
            onClick = { onEditSlot(Slot.RIGHT) },
        )
        SlotTarget(
            action = assignments[Slot.BOTTOM] ?: Slot.BOTTOM.default,
            diameter = size * 0.20f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * -0.085f, y = size * 0.345f),
            onClick = { onEditSlot(Slot.BOTTOM) },
        )

        // The fixed gear, shown greyed so it is obvious it is not up for grabs.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = size * 0.145f, y = size * 0.345f)
                .size(size * 0.145f)
                .clip(CircleShape)
                .background(Color(0xFF16171A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                tint = Color(Brand.OUTLINE),
                modifier = Modifier.size(size * 0.07f),
            )
        }
    }
}

@Composable
private fun SlotTarget(
    action: RemoteAction,
    diameter: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(
                if (action == RemoteAction.NONE) Color.Transparent else Color(Brand.SURFACE_HIGH),
                CircleShape,
            )
            .border(1.dp, action.color.copy(alpha = 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(action.icon(engaged = false)),
            contentDescription = action.label,
            tint = action.color,
            modifier = Modifier.size(diameter * 0.42f),
        )
    }
}

/** The "what can go here" list, reached by tapping a position. */
@Composable
fun SlotPickerScreen(
    slot: Slot,
    current: RemoteAction,
    onChoose: (RemoteAction) -> Unit,
) {
    val scroll = rememberScrollState()

    ScreenScaffold(scrollState = scroll) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListHeader { Text(slot.label) }

            RemoteAction.entries.forEach { action ->
                val selected = action == current
                Button(
                    onClick = { onChoose(action) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected) {
                        ButtonDefaults.buttonColors(
                            containerColor = action.dimColor,
                            contentColor = Color(Brand.ON_SURFACE),
                            secondaryContentColor = Color(Brand.ON_SURFACE_VARIANT),
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (selected) R.drawable.ic_check else action.icon(engaged = false)
                            ),
                            contentDescription = null,
                            tint = action.color,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    secondaryLabel = {
                        Text(
                            action.description,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    label = { Text(action.label) },
                )
            }
        }
    }
}
