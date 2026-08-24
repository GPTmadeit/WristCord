package com.wristcord.wear.link

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.wristcord.shared.ActionResult
import com.wristcord.shared.Capabilities
import com.wristcord.shared.IconVariant
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire
import com.wristcord.wear.tile.WristCordComplicationService
import kotlinx.coroutines.runBlocking

/**
 * Receives the phone's state pushes and per-command verdicts, even when the watch app is not
 * running - which is what lets the tile, the complication and the watch-face chip all show a
 * live Muted/Live label without anything of ours being open.
 */
class WearListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        // Play services starts this without the activity ever running, so the capability
        // guard belongs here too.
        Capabilities.ensure(
            ctx = this,
            declared = resources.getStringArray(com.wristcord.R.array.android_wear_capabilities),
            expected = Wire.CAPABILITY_WATCH,
        )
    }

    override fun onDataChanged(events: DataEventBuffer) {
        var latest: VoiceState? = null
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != Wire.PATH_STATE) continue

            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val state = VoiceState.fromDataMap(dataMap)
            StateStore.write(applicationContext, state)
            latest = state

            // Assets travel out of band, so resolving one means a second round trip. Only do it
            // when the fingerprint says the picture actually changed.
            if (state.artworkHash != ArtworkStore.currentHash(applicationContext)) {
                runBlocking {
                    ArtworkStore.update(
                        applicationContext,
                        dataMap.getAsset(Wire.KEY_ARTWORK),
                        state.artworkHash,
                    )
                }
            }

            Log.i(TAG, "state: inCall=${state.inCall} muted=${state.muted} deafened=${state.deafened}")
        }

        latest?.let { state ->
            Commands.requestTileUpdate(applicationContext)
            WristCordComplicationService.requestUpdate(applicationContext)
            OngoingService.syncWith(applicationContext, state)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Wire.PATH_RESULT -> {
                val result = runCatching { ActionResult.fromBytes(event.data) }.getOrNull() ?: return
                Log.i(TAG, "result: ${result.path} ok=${result.ok} ${result.message}")
                StateStore.onResult(applicationContext, result)
                Commands.requestTileUpdate(applicationContext)
                WristCordComplicationService.requestUpdate(applicationContext)
            }

            // The phone owns the icon choice for both devices; this is the watch applying it.
            Wire.PATH_ICON -> {
                val variant = IconVariant.byId(event.data.toString(Charsets.UTF_8).trim())
                if (variant == null) {
                    Log.w(TAG, "unknown icon variant from phone")
                    return
                }
                Log.i(TAG, "applying icon variant ${variant.id}")
                IconVariant.apply(applicationContext, variant)
            }

            else -> Unit
        }
    }

    private companion object {
        const val TAG = "WristCord/WatchLink"
    }
}
