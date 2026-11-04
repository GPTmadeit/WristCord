package com.wristcord.wear.voice

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.wristcord.wear.tile.WristCordComplicationService
import com.wristcord.wear.tile.WristCordTileService

/** Asks the tile and every placed complication to redraw. */
object Surfaces {
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(WristCordTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, WristCordComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
