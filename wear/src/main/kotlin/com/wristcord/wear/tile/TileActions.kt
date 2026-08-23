package com.wristcord.wear.tile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import android.os.Bundle
import com.wristcord.shared.Wire
import com.wristcord.wear.link.Commands
import com.wristcord.wear.link.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A tile tap has two possible shapes depending on the renderer:
 *
 *  - Renderers that support PENDING_INTENT_ACTION fire [TileActionReceiver] directly, so the
 *    tap never leaves the tile carousel - which is the whole point of putting mute on a tile.
 *  - Older renderers fall back to a LaunchAction, and [TileActionActivity] does the same work
 *    from a transparent activity that closes itself immediately.
 */
class TileActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        if (path !in Wire.ALL_ACTION_PATHS) return
        val app = context.applicationContext
        StateStore.load(app)

        // A receiver's process can be killed the moment onReceive returns; goAsync keeps it
        // alive until the Data Layer has actually taken the message.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Commands.fireAndWait(app, path)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "com.wristcord.TILE_PATH"

        fun pendingIntent(ctx: Context, path: String): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            path.hashCode(),
            Intent(ctx, TileActionReceiver::class.java)
                .setPackage(ctx.packageName)
                .putExtra(EXTRA_PATH, path),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** LaunchAction fallback target. Shows nothing and finishes straight away. */
class TileActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (path != null && path in Wire.ALL_ACTION_PATHS) {
            StateStore.load(applicationContext)
            Commands.fire(applicationContext, path)
        }
        // Theme.WristCord.Invisible already nulls windowAnimationStyle, so there is nothing
        // to cancel here and no deprecated transition API to call.
        finish()
    }

    companion object {
        const val EXTRA_PATH = "com.wristcord.TILE_PATH"
    }
}
