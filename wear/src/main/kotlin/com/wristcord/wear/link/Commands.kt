package com.wristcord.wear.link

import android.content.Context
import androidx.wear.tiles.TileService
import com.wristcord.shared.OutputDevice
import com.wristcord.shared.Wire
import com.wristcord.wear.tile.WristCordTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One place that fires a command at the phone, from either the app or the Tile.
 *
 * Every send is followed by a watchdog: if the phone has not replied with an [ActionResult]
 * inside [ANSWER_TIMEOUT_MS] the watch shows an error rather than sitting on a spinner.
 * Nothing here can end in silence.
 */
object Commands {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun mute(ctx: Context) = fire(ctx, Wire.PATH_MUTE)
    fun deafen(ctx: Context) = fire(ctx, Wire.PATH_DEAFEN)
    fun disconnect(ctx: Context) = fire(ctx, Wire.PATH_DISCONNECT)
    fun refresh(ctx: Context) = fire(ctx, Wire.PATH_REFRESH, quiet = true)

    fun chooseOutput(ctx: Context, device: OutputDevice) =
        fire(ctx, Wire.PATH_OUTPUT, device.encode().toByteArray(Charsets.UTF_8))

    fun fire(
        ctx: Context,
        path: String,
        payload: ByteArray = ByteArray(0),
        quiet: Boolean = false,
    ) {
        val app = ctx.applicationContext
        if (!quiet) StateStore.setPending(path)
        scope.launch {
            when (val result = PhoneLink(app).send(path, payload)) {
                is PhoneLink.Result.Sent -> if (!quiet) watchdog(app, path)
                is PhoneLink.Result.Failed -> {
                    StateStore.setError(app, result.message)
                    requestTileUpdate(app)
                }
            }
        }
    }

    /** Blocking variant for BroadcastReceiver.goAsync, which has no scope of its own. */
    suspend fun fireAndWait(ctx: Context, path: String) {
        val app = ctx.applicationContext
        StateStore.setPending(path)
        when (val result = PhoneLink(app).send(path)) {
            is PhoneLink.Result.Sent -> Unit
            is PhoneLink.Result.Failed -> StateStore.setError(app, result.message)
        }
        requestTileUpdate(app)
    }

    private suspend fun watchdog(ctx: Context, path: String) {
        delay(ANSWER_TIMEOUT_MS)
        if (StateStore.pending.value == path) {
            StateStore.setError(ctx, "No answer from the phone")
            requestTileUpdate(ctx)
        }
    }

    fun requestTileUpdate(ctx: Context) {
        runCatching {
            TileService.getUpdater(ctx.applicationContext)
                .requestUpdate(WristCordTileService::class.java)
        }
    }

    private const val ANSWER_TIMEOUT_MS = 15_000L
}
