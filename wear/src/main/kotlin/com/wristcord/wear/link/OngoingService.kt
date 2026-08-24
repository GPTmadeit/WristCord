package com.wristcord.wear.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wristcord.R
import com.wristcord.shared.Toggle
import com.wristcord.shared.VoiceState
import com.wristcord.wear.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps WristCord alive in the background while a call is running and puts an Ongoing Activity
 * chip on the watch face - the same affordance a running workout or a playing track gets. One
 * tap from the watch face lands you back on the remote with the call still up.
 *
 * Optional, and it stops itself the moment the call ends, so nothing is running when there is
 * nothing to control.
 *
 * Uses the `specialUse` foreground service type rather than `connectedDevice`: since API 34 the
 * latter is only permitted when a Bluetooth runtime permission is *granted*, and WristCord holds
 * no Bluetooth connection of its own - it talks to the phone through Play services' Data Layer.
 * Asking for a permission the app never uses, just to satisfy a type check, is not a trade worth
 * making.
 */
class OngoingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StateStore.load(this)
        createChannel()

        // A refused foreground start must not take the watch app down with it.
        if (!pushNotification(StateStore.state.value)) {
            stopSelf()
            return
        }

        scope.launch {
            StateStore.state.collectLatest { state ->
                if (!state.inCall || !isEnabled(this@OngoingService)) {
                    stopSelf()
                    return@collectLatest
                }
                if (!pushNotification(state)) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** @return true when the chip is up; false means we logged why and should stand down. */
    private fun pushNotification(state: VoiceState): Boolean {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val line = when {
            state.blocker.isNotBlank() -> state.blocker
            state.deafened == Toggle.ON -> "Deafened"
            state.muted == Toggle.ON -> "Muted"
            state.muted == Toggle.OFF -> "Live"
            else -> "Connected"
        }
        val title = state.channel.ifBlank { "Discord call" }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return try {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_tile)
                .setTouchIntent(open)
                .setStatus(Status.Builder().addTemplate("$title · $line").build())
                .build()
                .apply(applicationContext)

            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                // Type enforcement starts at API 34; below that the plain call is correct.
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not show the watch face chip", t)
            false
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Call remote", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows a chip on the watch face while a Discord call is up."
                    setShowBadge(false)
                    enableVibration(false)
                }
        )
    }

    companion object {
        private const val TAG = "WristCord/Ongoing"
        private const val CHANNEL_ID = "wristcord_ongoing"
        private const val NOTIFICATION_ID = 8123
        private const val PREFS = "wristcord_ongoing"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) syncWith(ctx, StateStore.state.value) else stop(ctx)
        }

        /** Called whenever new state lands: start while in a call, stop the moment it ends. */
        fun syncWith(ctx: Context, state: VoiceState) {
            if (state.inCall && isEnabled(ctx)) start(ctx) else stop(ctx)
        }

        private fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, OngoingService::class.java))
            }.onFailure { Log.w(TAG, "could not start the ongoing service", it) }
        }

        private fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, OngoingService::class.java)) }
        }

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
