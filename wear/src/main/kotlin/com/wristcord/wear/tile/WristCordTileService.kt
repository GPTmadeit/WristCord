package com.wristcord.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledVariantButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.wristcord.wear.MainActivity
import com.wristcord.wear.link.StateStore

/**
 * The four-button tile: Mute, Deafen, Output, Disconnect.
 *
 * Mute and Deafen carry live on/off state read from [StateStore], which the phone keeps
 * current through the Data Layer - the tile can be rendered in a cold process and still show
 * the truth. Taps go straight to the phone without opening the watch app; only Output needs
 * a screen, because it is a list.
 */
class WristCordTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val ctx = this@WristCordTileService
        val state = StateStore.readSync(ctx)
        val error = StateStore.readErrorSync(ctx)

        return tile(
            timeline = timeline(
                timelineEntry(
                    primaryLayout(
                        titleSlot = { text(titleFor(state).layoutString) },
                        mainSlot = { buttonGrid(ctx, state) },
                        bottomSlot = { text(footerFor(state, error).layoutString) },
                    )
                )
            )
        )
    }

    private fun MaterialScope.buttonGrid(ctx: Context, state: VoiceState): LayoutElement = column(
        buttonGroup(height = expand()) {
            buttonGroupItem {
                actionButton(
                    label = if (state.muted == Toggle.ON) "Unmute" else "Mute",
                    clickable = commandClickable(ctx, "mute", Wire.PATH_MUTE),
                    highlighted = state.muted == Toggle.ON,
                )
            }
            buttonGroupItem {
                actionButton(
                    label = if (state.deafened == Toggle.ON) "Undeafen" else "Deafen",
                    clickable = commandClickable(ctx, "deafen", Wire.PATH_DEAFEN),
                    highlighted = state.deafened == Toggle.ON,
                )
            }
        },
        rowGap(),
        buttonGroup(height = expand()) {
            buttonGroupItem {
                actionButton(
                    label = "Output",
                    clickable = openOutputsClickable(ctx),
                    highlighted = false,
                )
            }
            buttonGroupItem {
                actionButton(
                    label = "Leave",
                    clickable = commandClickable(ctx, "disconnect", Wire.PATH_DISCONNECT),
                    highlighted = true,
                )
            }
        },
        width = expand(),
        height = expand(),
    )

    /** protolayout-material3's own verticalSpacer is internal, so build the Spacer directly. */
    private fun rowGap(): LayoutElement =
        Spacer.Builder().setHeight(dp(SPACING_DP.toFloat())).build()

    private fun MaterialScope.actionButton(
        label: String,
        clickable: Clickable,
        highlighted: Boolean,
    ): LayoutElement = textButton(
        onClick = clickable,
        labelContent = { text(label.layoutString) },
        width = expand(),
        height = expand(),
        colors = if (highlighted) filledVariantButtonColors() else filledTonalButtonColors(),
    )

    /**
     * Preferred path: a PendingIntent the renderer fires directly, so the tap is handled
     * without leaving the carousel. Renderers without PENDING_INTENT_ACTION get a LaunchAction
     * to [TileActionActivity], which does exactly the same thing and closes itself.
     */
    private fun MaterialScope.commandClickable(ctx: Context, id: String, path: String): Clickable =
        protoLayoutScope.clickable(
            pendingIntent = TileActionReceiver.pendingIntent(ctx, path),
            id = id,
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
