package com.wristcord.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import com.wristcord.shared.Brand
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.VoiceState
import com.wristcord.wear.MainActivity
import com.wristcord.wear.link.SlotConfig
import com.wristcord.wear.link.StateStore

/**
 * The tile mirrors the app: the CENTRE slot gets a full-width primary button, the other three
 * sit under it. Same Google colours, same slot assignments - change the layout in Settings and
 * the tile follows, because both read [SlotConfig].
 */
class WristCordTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val ctx = this@WristCordTileService
        val state = StateStore.readSync(ctx)
        val error = StateStore.readErrorSync(ctx)
        val slots = SlotConfig.readSync(ctx)

        return tile(
            timeline = timeline(
                timelineEntry(
                    primaryLayout(
                        titleSlot = { text(titleFor(state).layoutString) },
                        mainSlot = { buttonGrid(ctx, state, slots) },
                        bottomSlot = { text(footerFor(state, error).layoutString) },
                    )
                )
            )
        )
    }

    private fun MaterialScope.buttonGrid(
        ctx: Context,
        state: VoiceState,
        slots: Map<Slot, RemoteAction>,
    ): LayoutElement {
        val centre = slots[Slot.CENTRE] ?: Slot.CENTRE.default
        val others = listOf(Slot.LEFT, Slot.RIGHT, Slot.BOTTOM)
            .map { slots[it] ?: it.default }
            .filter { it != RemoteAction.NONE }

        return column(
            // Primary row is deliberately taller - the same "one button you can hit blind"
            // idea the app's centre disc is built around.
            buttonGroup(height = weight(1.5f)) {
                buttonGroupItem {
                    actionButton(ctx, centre, centre.centreLabelFor(state), engaged(centre, state))
                }
            },
            rowGap(),
            buttonGroup(height = weight(1f)) {
                others.forEach { action ->
                    buttonGroupItem {
                        actionButton(ctx, action, action.label, engaged(action, state))
                    }
                }
            },
            width = expand(),
            height = expand(),
        )
    }

    private fun MaterialScope.actionButton(
        ctx: Context,
        action: RemoteAction,
        label: String,
        engaged: Boolean,
    ): LayoutElement = textButton(
        onClick = clickableFor(ctx, action),
        labelContent = { text(label.layoutString) },
        width = expand(),
        height = expand(),
        colors = colorsFor(action, engaged),
    )

    /** Engaged fills with the action's colour; resting is a dark disc with a coloured label. */
    private fun colorsFor(action: RemoteAction, engaged: Boolean): ButtonColors = if (engaged) {
        ButtonColors(
            containerColor = action.accent.argb,
            iconColor = onAccent(action).argb,
            labelColor = onAccent(action).argb,
            secondaryLabelColor = onAccent(action).argb,
        )
    } else {
        ButtonColors(
            containerColor = Brand.SURFACE_HIGH.argb,
            iconColor = action.accent.argb,
            labelColor = action.accent.argb,
            secondaryLabelColor = Brand.ON_SURFACE_VARIANT.argb,
        )
    }

    private fun onAccent(action: RemoteAction): Int =
        if (action == RemoteAction.DEAFEN) Brand.INK else 0xFFFFFFFF.toInt()

    private fun MaterialScope.clickableFor(ctx: Context, action: RemoteAction): Clickable =
        if (action.opensList) openOutputsClickable(ctx) else commandClickable(ctx, action)

    /**
     * Preferred path: a PendingIntent the renderer fires directly, so the tap is handled
     * without leaving the carousel. Renderers without PENDING_INTENT_ACTION get a LaunchAction
     * to [TileActionActivity], which does the same thing and closes itself.
     */
    private fun MaterialScope.commandClickable(ctx: Context, action: RemoteAction): Clickable {
        val path = action.path ?: return clickable(id = action.id)
        return protoLayoutScope.clickable(
            pendingIntent = TileActionReceiver.pendingIntent(ctx, path),
            id = action.id,
            fallbackAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(ctx.packageName)
                        .setClassName(TileActionActivity::class.java.name)
                        .addKeyToExtraMapping(
                            TileActionActivity.EXTRA_PATH,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(path).build(),
                        )
                        .build()
                )
                .build(),
        )
    }

    /** Output is a list, so this one deliberately opens the watch app on that screen. */
    private fun openOutputsClickable(ctx: Context): Clickable = clickable(
        action = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(ctx.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(
                        MainActivity.EXTRA_OPEN_OUTPUTS,
                        ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build(),
                    )
                    .build()
            )
            .build(),
        id = "output",
    )

    /** protolayout-material3's own verticalSpacer is internal, so build the Spacer directly. */
    private fun rowGap(): LayoutElement =
        Spacer.Builder().setHeight(dp(SPACING_DP.toFloat())).build()

    private fun engaged(action: RemoteAction, state: VoiceState): Boolean = when (action) {
        RemoteAction.MUTE -> state.muted == com.wristcord.shared.Toggle.ON
        RemoteAction.DEAFEN -> state.deafened == com.wristcord.shared.Toggle.ON
        else -> false
    }

    private fun RemoteAction.centreLabelFor(state: VoiceState): String = when (this) {
        RemoteAction.MUTE -> if (state.muted == com.wristcord.shared.Toggle.ON) "Unmute" else "Mute"
        RemoteAction.DEAFEN ->
            if (state.deafened == com.wristcord.shared.Toggle.ON) "Undeafen" else "Deafen"

        else -> label
    }

    private fun titleFor(state: VoiceState): String = when {
        state.channel.isNotBlank() -> state.channel
        state.inCall -> "In a call"
        else -> "WristCord"
    }

    private fun footerFor(state: VoiceState, error: String?): String = when {
        error != null -> error
        state.blocker.isNotBlank() -> state.blocker
        !state.inCall -> "Not in a voice call"
        else -> state.outputs.firstOrNull { it.active }?.label ?: "Connected"
    }

    private companion object {
        const val SPACING_DP = 4
    }
}
