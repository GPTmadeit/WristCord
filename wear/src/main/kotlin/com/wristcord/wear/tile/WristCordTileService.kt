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
import androidx.wear.protolayout.material3.TextButtonStyle
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
import androidx.compose.ui.graphics.toArgb
import com.wristcord.shared.RemoteAction
import com.wristcord.shared.Slot
import com.wristcord.shared.VoiceState
import com.wristcord.wear.MainActivity
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.discord.ThemePrefs
import com.wristcord.wear.discord.TokenVault
import com.wristcord.wear.discord.Voice
import com.wristcord.wear.voice.SlotConfig
import com.wristcord.wear.ui.remoteColors
import com.wristcord.wear.ui.theme.DiscordColors
import com.wristcord.wear.ui.theme.tokens

/**
 * The tile mirrors the app: the CENTRE slot gets a full-width primary button, the other three
 * sit under it. Same Discord theme, same slot assignments - change the layout in Settings and
 * the tile follows, because both read [SlotConfig]. During a call on the watch the buttons act
 * on it directly; a call on another device is shown as Discord reports it, and tapping opens
 * the remote. Looking at the tile asks Discord for a fresh reading.
 */
class WristCordTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val ctx = this@WristCordTileService
        val state = Voice.mergedSync(ctx)
        val error: String? = null
        val slots = SlotConfig.readSync(ctx)
        palette = ThemePrefs.read(ctx).theme.tokens()
        // Someone is looking at the tile: bring Discord's state up to date for the next frame.
        if (TokenVault(ctx).hasToken) WristCordApp.of(ctx).account.touch("tile")

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
                        // Three across leaves room for a short word in the small style, not a
                        // full label. (Inline icons need renderer support the Material3 tile
                        // service only uses when the watch advertises it.)
                        actionButton(ctx, action, action.shortLabel, engaged(action, state), small = true)
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
        small: Boolean = false,
    ): LayoutElement = textButton(
        onClick = clickableFor(ctx, action),
        labelContent = { text(label.layoutString) },
        width = expand(),
        height = expand(),
        colors = colorsFor(action, engaged),
        style = if (small) TextButtonStyle.smallTextButtonStyle() else TextButtonStyle.defaultTextButtonStyle(),
    )

    private val RemoteAction.shortLabel: String
        get() = when (this) {
            RemoteAction.MUTE -> "Mic"
            RemoteAction.DEAFEN -> "Deaf"
            RemoteAction.OUTPUT -> "Out"
            RemoteAction.DISCONNECT -> "End"
            RemoteAction.REFRESH -> "Sync"
            RemoteAction.NONE -> ""
        }

    private var palette: DiscordColors = DiscordColors.Onyx

    /** Discord's voice-panel colours: neutral at rest, red when muted/deafened, red hang-up. */
    private fun colorsFor(action: RemoteAction, engaged: Boolean): ButtonColors {
        val c = remoteColors(palette, action, engaged)
        return ButtonColors(
            containerColor = c.fill.toArgb().argb,
            iconColor = c.tint.toArgb().argb,
            labelColor = c.tint.toArgb().argb,
            secondaryLabelColor = palette.textMuted.toArgb().argb,
        )
    }

    private fun MaterialScope.clickableFor(ctx: Context, action: RemoteAction): Clickable =
        if (action.opensList) openOutputsClickable(ctx) else commandClickable(ctx, action)

    /**
     * Preferred path: a PendingIntent the renderer fires directly, so the tap is handled
     * without leaving the carousel. Renderers without PENDING_INTENT_ACTION get a LaunchAction
     * to [TileActionActivity], which does the same thing and closes itself.
     */
    private fun MaterialScope.commandClickable(ctx: Context, action: RemoteAction): Clickable {
        if (action == RemoteAction.NONE) return clickable(id = action.id)
        return protoLayoutScope.clickable(
            pendingIntent = TileActionReceiver.pendingIntent(ctx, action.id),
            id = action.id,
            fallbackAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(ctx.packageName)
                        .setClassName(TileActionActivity::class.java.name)
                        .addKeyToExtraMapping(
                            TileActionActivity.EXTRA_ACTION,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(action.id).build(),
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
        !state.inCall -> "Not in a call"
        else -> state.outputs.firstOrNull { it.active }?.label ?: "Connected"
    }

    private companion object {
        const val SPACING_DP = 4
    }
}
