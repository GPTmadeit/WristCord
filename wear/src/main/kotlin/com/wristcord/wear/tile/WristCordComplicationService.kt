package com.wristcord.wear.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wristcord.R
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.wear.MainActivity
import com.wristcord.wear.discord.Voice

/**
 * A watch-face complication: current mute state at a glance, one tap to open the remote.
 *
 * Optional - the user adds it from the watch face editor like any other complication. It reads
 * the same state the tile does.
 */
class WristCordComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // The watch's own call, or Discord's report of a call on another device.
        val state = Voice.mergedSync(this)
        return build(request.complicationType, state)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(
        type,
        VoiceState(inCall = true, channel = "General", muted = Toggle.ON),
    )

    private fun build(type: ComplicationType, state: VoiceState): ComplicationData? {
        val label = when {
            !state.inCall -> "--"
            state.deafened == Toggle.ON -> "DEAF"
            state.muted == Toggle.ON -> "MUTE"
            state.muted == Toggle.OFF -> "LIVE"
            else -> "??"
        }
        val spoken = when {
            !state.inCall -> "WristCord, not in a call"
            state.deafened == Toggle.ON -> "WristCord, deafened"
            state.muted == Toggle.ON -> "WristCord, muted"
            state.muted == Toggle.OFF -> "WristCord, microphone live"
            else -> "WristCord, state unknown"
        }
        val iconRes = when {
            !state.inCall -> R.drawable.ic_tile
            state.deafened == Toggle.ON -> R.drawable.ic_headset_off
            state.muted == Toggle.ON -> R.drawable.ic_mic_off
            else -> R.drawable.ic_mic
        }

        val description = PlainComplicationText.Builder(spoken).build()
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(label).build(),
                contentDescription = description,
            )
                .setTitle(PlainComplicationText.Builder("Discord").build())
                .setMonochromaticImage(image)
                .setTapAction(openApp())
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = image,
                contentDescription = description,
            )
                .setTapAction(openApp())
                .build()

            // Anything else: leave whatever is there alone rather than blanking the slot.
            else -> null
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_REMOTE, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Nudge every placed instance after the call state changes. */
        fun requestUpdate(ctx: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(
                        ctx.applicationContext,
                        ComponentName(ctx.applicationContext, WristCordComplicationService::class.java),
                    )
                    .requestUpdateAll()
            }
        }
    }
}
