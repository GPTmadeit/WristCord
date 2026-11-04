package com.wristcord.wear.tile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.wristcord.shared.RemoteAction
import com.wristcord.wear.MainActivity
import com.wristcord.wear.WristCordApp
import com.wristcord.wear.voice.Surfaces

/**
 * A tile tap has two possible shapes depending on the renderer:
 *
 *  - Renderers that support PENDING_INTENT_ACTION fire [TileActionReceiver] directly, so the
 *    tap never leaves the tile carousel - which is the whole point of putting mute on a tile.
 *  - Older renderers fall back to a LaunchAction, and [TileActionActivity] does the same work
 *    from a transparent activity that closes itself immediately.
 *
 * Mute, deafen and leave act on the watch's own call. Anything else - or a call that is on
 * another device - opens the remote instead, because joining needs the screen.
 */
object TileCommand {
    fun run(ctx: Context, actionId: String) {
        val app = WristCordApp.of(ctx)
        val call = app.voiceCall
        val action = RemoteAction.byId(actionId) ?: return
        if (!call.isOnWatch) {
            open(ctx)
            return
        }
        when (action) {
            RemoteAction.MUTE -> call.toggleMute()
            RemoteAction.DEAFEN -> call.toggleDeafen()
            RemoteAction.DISCONNECT -> call.leave()
            else -> open(ctx)
        }
        Surfaces.refresh(ctx)
    }

    private fun open(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_REMOTE, true)
        )
    }
}

class TileActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_ACTION)?.let { TileCommand.run(context.applicationContext, it) }
    }

    companion object {
        const val EXTRA_ACTION = "com.wristcord.TILE_ACTION"

        fun pendingIntent(ctx: Context, actionId: String): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            actionId.hashCode(),
            Intent(ctx, TileActionReceiver::class.java)
                .setPackage(ctx.packageName)
                .putExtra(EXTRA_ACTION, actionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** LaunchAction fallback target. Shows nothing and finishes straight away. */
class TileActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_ACTION)?.let { TileCommand.run(applicationContext, it) }
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "com.wristcord.TILE_ACTION"
    }
}
