package com.wristcord.wear.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wristcord.R
import com.wristcord.wear.MainActivity
import com.wristcord.wear.WristCordApp

/**
 * Keeps the gateway connected so messages can arrive with the app closed. Discord's push goes
 * through Firebase to Discord's own signed app, so a third-party client has no other way in.
 *
 * Holding the CPU awake all day would flatten a watch, so it isn't. The watch sleeps, and these
 * bring it back to check the socket: an incoming packet wakes it by itself; a repeating alarm
 * ([KeepAliveReceiver]) catches a socket that died quietly; the screen coming on and the network
 * returning do too. A message that lands on a dead socket therefore waits for the next of those,
 * and the session's RESUME then replays it.
 */
class NotificationService : Service() {
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pulse(context, "screen on")
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = pulse(this@NotificationService, "network")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while WristCord stays connected for notifications"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WristCord")
            .setContentText("Connected for notifications")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        // As an Ongoing Activity it stays out of the notification list, where it would otherwise
        // sit on top of real messages all day.
        val notification = runCatching {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_notification)
                .setTouchIntent(open)
                .setStatus(Status.Builder().addTemplate("Connected for notifications").build())
                .build()
                .apply(applicationContext)
        }.let { builder.build() }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        running = true
        WristCordApp.of(this).account.acquire(HOLD)
        scheduleKeepAlive(this)
        Log.i(TAG, "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start already on its way when the setting changed: it had to reach startForeground
        // first, so this is the first moment it can safely stop.
        if (!isWanted(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(screenOn) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        cancelKeepAlive(this)
        WristCordApp.of(this).account.release(HOLD)
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WristCord/Notify"
        private const val HOLD = "notifications"
        private const val CHANNEL_ID = "wristcord_connection"
        private const val NOTIFICATION_ID = 8199
        private const val KEEP_ALIVE_MS = 3 * 60_000L
        /** Long enough for a heartbeat's answer, or a reconnect and its RESUME. */
        private const val PULSE_WAKE_MS = 30_000L

        @Volatile private var running = false
        /** Debug builds: run with the demo account, which has no token. */
        @Volatile private var forced = false

        private fun isWanted(ctx: Context) = forced ||
            NotificationPrefs.mode.value != NotificationPrefs.Mode.OFF &&
                WristCordApp.of(ctx).account.hasToken &&
                NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        /** Starts or stops the service to match the setting, the sign-in and the permission. */
        fun sync(ctx: Context, force: Boolean = false) {
            if (force) forced = true
            val intent = Intent(ctx, NotificationService::class.java)
            if (isWanted(ctx)) {
                // Refused when the app is in the background with no exemption; boot and the app
                // opening both come through here again.
                runCatching { ContextCompat.startForegroundService(ctx, intent) }
                    .onFailure { Log.i(TAG, "not started now: ${it.javaClass.simpleName}") }
            } else if (running) {
                // Never before it has gone foreground: Android kills an app for stopping a
                // foreground start early. onStartCommand handles that case instead.
                ctx.stopService(intent)
            }
        }

        /** An alarm already on its way when the service stopped must not start the cycle again. */
        fun onKeepAlive(ctx: Context) {
            if (!running) return
            pulse(ctx, "alarm")
            scheduleKeepAlive(ctx)
        }

        fun pulse(ctx: Context, why: String) {
            val app = WristCordApp.of(ctx)
            ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristCord:pulse")
                .acquire(PULSE_WAKE_MS)
            Log.d(TAG, "pulse: $why, gateway ${app.discord.gateway.state.value.javaClass.simpleName}")
            app.discord.gateway.pulse()
        }

        private fun keepAliveIntent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, KeepAliveReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Inexact on purpose: the system lines it up with other wake-ups and stretches it in Doze. */
        fun scheduleKeepAlive(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + KEEP_ALIVE_MS, keepAliveIntent(ctx),
            )
        }

        private fun cancelKeepAlive(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(keepAliveIntent(ctx))
        }
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationService.onKeepAlive(context)
    }
}

/** After a reboot or a self-update, neither of which leaves anything running. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        NotificationPrefs.load(context)
        NotificationService.sync(context)
    }
}
