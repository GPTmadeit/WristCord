package com.wristcord.wear.link

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.wristcord.shared.ActionResult
import com.wristcord.shared.VoiceState
import com.wristcord.shared.Wire

/**
 * Receives the phone's state pushes and per-command verdicts, even when the watch app is not
 * running - which is what lets the Tile show a live Muted/Unmuted label.
 */
class WearListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        var changed = false
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != Wire.PATH_STATE) continue
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val state = VoiceState.fromDataMap(map)
            StateStore.write(applicationContext, state)
            changed = true
            Log.i(TAG, "state: inCall=${state.inCall} muted=${state.muted} deafened=${state.deafened}")
        }
        if (changed) Commands.requestTileUpdate(applicationContext)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Wire.PATH_RESULT) return
        val result = runCatching { ActionResult.fromBytes(event.data) }.getOrNull() ?: return
        Log.i(TAG, "result: ${result.path} ok=${result.ok} ${result.message}")
        StateStore.onResult(applicationContext, result)
        Commands.requestTileUpdate(applicationContext)
    }

    private companion object {
        const val TAG = "WristCord/WatchLink"
    }
}
